package com.applauncher.dual;

import android.content.Context;

import com.carlos.common.App;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * 模块配置的「安全区」目录（safe zone）。
 *
 * 为什么需要它：模块自己的设置界面写 SharedPreferences 时，正常落到
 * /data/user/0/&lt;模块包名&gt;/shared_prefs/，而目标 App 的分身进程（跑在我们 uid 下）
 * 没有权限读别的 App 的私有目录，于是模块只能读不到配置 → 永远用默认值。
 *
 * Vector/LSPosed 的解法：把模块界面进程里 ContextImpl.getPreferencesDir 重定向到
 * 「框架指定的目录」（IFrameworkService.getPrefsPath）。我们的 getPrefsPath 就返回这里：
 *   &lt;宿主 filesDir&gt;/lsp_prefs/&lt;模块包名&gt;/
 * 两边都是我们自己的 uid，读写畅通；本类负责该目录的枚举、读取、改写和清空。
 */
public class LspPrefs {

    public static final String DIR = "lsp_prefs";

    public static File baseDir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        // 让 guest（同 uid）也能读写
        d.setReadable(true, false);
        d.setExecutable(true, false);
        return d;
    }

    public static File dirFor(Context c, String pkg) {
        File d = new File(baseDir(c), pkg);
        if (!d.isDirectory()) d.mkdirs();
        d.setReadable(true, false);
        d.setExecutable(true, false);
        return d;
    }

    public static String pathFor(Context c, String pkg) {
        return dirFor(c, pkg).getAbsolutePath();
    }

    public static List<File> prefFiles(Context c, String pkg) {
        List<File> out = new ArrayList<>();
        File[] fs = dirFor(c, pkg).listFiles((dir, n) -> n.endsWith(".xml"));
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
            java.util.Collections.addAll(out, fs);
        }
        return out;
    }

    /** 读一个 xml 里的所有条目：key -> value（类型信息存在 Entry.type 里） */
    public static List<Entry> read(Context c, String pkg) {
        List<Entry> out = new ArrayList<>();
        for (File f : prefFiles(c, pkg)) {
            try {
                for (Map.Entry<String, String> e : readXml(f).entrySet()) {
                    Entry en = new Entry();
                    en.file = f.getName();
                    en.key = e.getKey();
                    en.value = e.getValue();
                    out.add(en);
                }
            } catch (Throwable t) {
                App.log("LspPrefs read " + f + " failed", t);
            }
        }
        return out;
    }

    public static class Entry {
        public String file;
        public String key;
        public String value;

        @Override
        public String toString() {
            return key + " = " + value + "    (" + file + ")";
        }
    }

    /** 把一个 key 改成新值（支持 string / boolean / int / long / float，按原类型写回） */
    public static boolean write(Context c, String pkg, String fileName, String key, String newValue) {
        File f = new File(dirFor(c, pkg), fileName);
        try {
            Document doc = parse(f);
            if (doc == null) return false;
            NodeList nodes = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element el = (Element) n;
                if (!key.equals(el.getAttribute("name"))) continue;
                // 类型标签：<string> <boolean> <int> <long> <float> <set>
                String tag = el.getTagName();
                if ("set".equals(tag)) {
                    // 字符串集合：一行一个，用 \n 分隔
                    NodeList subs = el.getElementsByTagName("string");
                    while (subs.getLength() > 0) el.removeChild(subs.item(0));
                    for (String line : newValue.split("\n")) {
                        if (line.trim().isEmpty()) continue;
                        Element s = doc.createElement("string");
                        s.setTextContent(line);
                        el.appendChild(s);
                    }
                } else if ("boolean".equals(tag)) {
                    el.setTextContent(String.valueOf(parseBool(newValue)));
                } else {
                    // 其它类型试着保类型，失败就退回字符串
                    if ("string".equals(tag)) {
                        el.setTextContent(newValue);
                    } else if (isNumeric(el.getTextContent())) {
                        try {
                            Long.parseLong(el.getTextContent());
                            long v = Long.parseLong(newValue.trim());
                            el.setTextContent(String.valueOf(v));
                        } catch (Throwable t) {
                            // 用户敲了非数字：直接改类型不划算，原样塞进 string
                            doc.renameNode(el, null, "string");
                            el.setTextContent(newValue);
                        }
                    } else {
                        el.setTextContent(newValue);
                    }
                }
                write(f, doc);
                App.log("LspPrefs write " + pkg + " " + fileName + " " + key + "=" + newValue);
                return true;
            }
            App.log("LspPrefs write: key not found " + key + " in " + fileName);
            return false;
        } catch (Throwable t) {
            App.log("LspPrefs write failed " + key, t);
            return false;
        }
    }

    /** 模块新建一个 xml 条目（key 不存在时用） */
    public static boolean put(Context c, String pkg, String fileName, String key, String newValue) {
        File f = new File(dirFor(c, pkg), fileName);
        try {
            Document doc;
            if (f.isFile()) {
                doc = parse(f);
            } else {
                doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                doc.appendChild(doc.createElement("map"));
            }
            if (doc == null) return false;
            NodeList nodes = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE
                        && key.equals(((Element) n).getAttribute("name"))) {
                    return write(c, pkg, fileName, key, newValue);
                }
            }
            String type = "string";
            if ("true".equalsIgnoreCase(newValue) || "false".equalsIgnoreCase(newValue)) type = "boolean";
            else if (newValue.matches("-?\\d+")) type = "int";
            Element el = doc.createElement(type);
            el.setAttribute("name", key);
            el.setTextContent(newValue);
            doc.getDocumentElement().appendChild(el);
            write(f, doc);
            return true;
        } catch (Throwable t) {
            App.log("LspPrefs put failed " + key, t);
            return false;
        }
    }

    public static void clear(Context c, String pkg) {
        File dir = new File(dirFor(c, pkg).getAbsolutePath());
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.delete()) App.log("LspPrefs clear: cannot delete " + f);
        }
        App.log("LspPrefs clear " + pkg);
    }

    // ---------------------------------------------------------------- xml

    private static Document parse(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            return db.parse(in);
        } finally {
            in.close();
        }
    }

    public static LinkedHashMap<String, String> readXml(File f) throws Exception {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Document doc = parse(f);
        if (doc == null) return map;
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String key = el.getAttribute("name");
            if ("set".equals(el.getTagName())) {
                StringBuilder sb = new StringBuilder();
                NodeList subs = el.getElementsByTagName("string");
                for (int j = 0; j < subs.getLength(); j++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(subs.item(j).getTextContent());
                }
                map.put(key, sb.toString());
            } else {
                map.put(key, el.getTextContent());
            }
        }
        return map;
    }

    private static void write(File f, Document doc) throws Exception {
        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        FileOutputStream out = new FileOutputStream(f);
        try {
            tr.transform(new DOMSource(doc), new StreamResult(out));
            out.flush();
        } finally {
            out.close();
        }
        f.setReadable(true, false);
    }

    private static boolean parseBool(String v) {
        return "true".equalsIgnoreCase(v == null ? "" : v.trim());
    }

    private static boolean isNumeric(String s) {
        return s != null && s.trim().matches("-?\\d+(\\.\\d+)?");
    }

    // ---------------------------------------------------------------- 激活状态

    private static File markerFile(Context c) {
        return new File(new File(c.getFilesDir(), "npatch"), "activated.txt");
    }

    /** 最近一次启动里，框架是否真的把这个模块加载进了 guest（驱动写的 marker） */
    public static long activatedAt(Context c, String pkg) {
        File f = markerFile(c);
        if (!f.isFile()) return -1;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                long ts = -1;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split("\t");
                    if (p.length >= 2 && pkg.equals(p[0])) {
                        try { ts = Math.max(ts, Long.parseLong(p[1])); } catch (Throwable ignored) { }
                    }
                }
                return ts;
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean isActivated(Context c, String pkg) {
        return activatedAt(c, pkg) > 0;
    }

    /** 驱动在 loadLegacyModules 之后写的标记，宿主 UI 读它显示「已激活」 */
    public static void clearMarker(Context c) {
        File f = markerFile(c);
        if (f.isFile() && !f.delete()) App.log("clearMarker failed");
    }
}
