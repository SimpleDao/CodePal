package com.codepal.utils;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown 渲染工具：将 Markdown 文本转换为带 CSS 样式的 HTML 字符串。
 * 兼容 JBCefBrowser（Chromium 内核）和 JEditorPane（Swing 回退），
 * 颜色格式统一为 #RRGGBB，自动适配 IDEA 暗色/亮色主题。
 */
public final class MarkdownUtil {

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        List<org.commonmark.Extension> extensions = Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create()
        );
        PARSER = Parser.builder().extensions(extensions).build();
        RENDERER = HtmlRenderer.builder().extensions(extensions).build();
    }

    private MarkdownUtil() {}

    /**
     * 将 Markdown 文本转换为完整的 HTML 页面（含 CSS）。
     * <p>生成的 HTML 兼容 JBCefBrowser（Chromium 内核）和 JEditorPane（Swing 引擎）。
     * 颜色格式统一为 #RRGGBB，避免 Swing CSS 解析器崩溃。
     *
     * @param markdown   原始 Markdown 文本
     * @param isDark     是否暗色主题
     * @param bubbleBg   气泡背景色（CSS 颜色，用于代码块背景）
     * @return 完整 HTML 字符串
     */
    public static String toHtml(String markdown, boolean isDark, Color bubbleBg) {
        return toHtml(markdown, isDark, bubbleBg, null);
    }

    /**
     * 将 Markdown 文本转换为完整的 HTML 页面（含 CSS），支持自定义文字颜色。
     *
     * @param markdown       原始 Markdown 文本
     * @param isDark         是否暗色主题
     * @param bubbleBg       气泡背景色
     * @param customTextColor 自定义文字颜色（null 则使用主题默认色）
     * @return 完整 HTML 字符串
     */
    public static String toHtml(String markdown, boolean isDark, Color bubbleBg, Color customTextColor) {
        Node document = PARSER.parse(markdown);
        String bodyHtml = RENDERER.render(document);

        // 基础颜色（新UI暗色主题适配）
        String textColor    = customTextColor != null ? colorToHex(customTextColor)
                : (isDark ? "#E0E0E0" : "#1a1a1a");
        String bgColor      = colorToHex(bubbleBg);
        String codeBg       = isDark ? "#1A1A1A" : "#F0F0F0";
        String codeColor    = isDark ? "#D4D4D4" : "#2B2B2B";
        String codeBorder   = isDark ? "#3C3F41" : "#D0D0D0";
        String linkColor    = isDark ? "#589DF6" : "#2563EB";
        String blockquoteBg = isDark ? "#252525" : "#F5F5F5";
        String blockquoteBdr= isDark ? "#4A90D9" : "#3B82F6";
        String tableBorder  = isDark ? "#3C3F41" : "#D0D0D0";
        String tableHeadBg  = isDark ? "#2D2D2D" : "#E8E8E8";
        String hrColor      = isDark ? "#3C3F41" : "#D0D0D0";

        return "<!DOCTYPE html><html><head><meta charset='utf-8'><style>" +
                "* { box-sizing: border-box; margin: 0; padding: 0; }" +
                "html { overflow-y: hidden; }" +  // 关键：禁止 HTML 页面自身滚动，防止截断
                "body {" +
                "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;" +
                "  font-size: 14px;" +
                "  line-height: 1.8;" +
                "  color: " + textColor + ";" +
                "  background: " + bgColor + ";" +
                "  padding: 4px 0;" +
                "  margin: 0;" +
                "  word-break: break-word;" +
                "  overflow-wrap: break-word;" +
                "  -webkit-user-select: text;" +
                "  user-select: text;" +
                "  overflow: visible;" +  // 关键：允许内容溢出可见，避免被截断
                "}" +
                // 段落
                "p { margin: 0 0 8px 0; }" +
                "p:last-child { margin-bottom: 0; }" +
                // 标题
                "h1,h2,h3,h4,h5,h6 { font-weight: 600; margin: 16px 0 8px 0; line-height: 1.4; }" +
                "h1 { font-size: 20px; } h2 { font-size: 17px; } h3 { font-size: 15px; }" +
                "h1:first-child,h2:first-child,h3:first-child { margin-top: 0; }" +
                // 行内代码
                "code {" +
                "  font-family: 'JetBrains Mono', 'Consolas', 'Monaco', 'Courier New', monospace;" +
                "  font-size: 13px;" +
                "  background: " + codeBg + ";" +
                "  color: " + codeColor + ";" +
                "  padding: 2px 6px;" +
                "  border-radius: 4px;" +
                "  border: 1px solid " + codeBorder + ";" +
                "}" +
                // 代码块
                "pre {" +
                "  background: " + codeBg + ";" +
                "  border: 1px solid " + codeBorder + ";" +
                "  border-radius: 8px;" +
                "  padding: 12px 16px;" +
                "  margin: 10px 0;" +
                "  overflow-x: auto;" +
                "  line-height: 1.55;" +
                "}" +
                "pre code {" +
                "  background: none;" +
                "  border: none;" +
                "  padding: 0;" +
                "  font-size: 12px;" +
                "  color: " + codeColor + ";" +
                "  white-space: pre;" +
                "}" +
                // 引用块
                "blockquote {" +
                "  background: " + blockquoteBg + ";" +
                "  border-left: 4px solid " + blockquoteBdr + ";" +
                "  margin: 10px 0;" +
                "  padding: 8px 14px;" +
                "  border-radius: 0 6px 6px 0;" +
                "}" +
                "blockquote p:last-child { margin-bottom: 0; }" +
                // 列表
                "ul, ol { padding-left: 24px; margin: 8px 0; }" +
                "li { margin: 4px 0; }" +
                "li p { margin: 0 0 4px 0; }" +
                // 链接
                "a { color: " + linkColor + "; text-decoration: none; }" +
                "a:hover { text-decoration: underline; }" +
                // 表格
                "table { border-collapse: collapse; width: 100%; margin: 8px 0; }" +
                "th, td { border: 1px solid " + tableBorder + "; padding: 6px 10px; text-align: left; }" +
                "th { background: " + tableHeadBg + "; font-weight: bold; }" +
                // 水平线
                "hr { border: none; border-top: 1px solid " + hrColor + "; margin: 10px 0; }" +
                // 加粗/斜体
                "strong { font-weight: bold; }" +
                "em { font-style: italic; }" +
                // 删除线
                "del { text-decoration: line-through; opacity: 0.7; }" +
                "</style></head><body>" +
                bodyHtml +
                "</body></html>";
    }

    /**
     * 将 Markdown 渲染为 body-only HTML 片段（用于注入 ChatWebView 的 DOM）。
     * 不含 &lt;html&gt;/&lt;head&gt;/&lt;body&gt; 标签，CSS 由 ChatHtmlTemplate 页面统一管理。
     */
    public static String toHtmlFragment(String markdown) {
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document);
    }

    private static String colorToHex(Color c) {
        if (c == null) return "transparent";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 判断当前 IDE 是否为暗色主题 */
    public static boolean isDarkTheme() {
        // 方式1：使用 Panel.background
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            // 方式2：使用 EditorPane.background
            bg = UIManager.getColor("EditorPane.background");
        }
        if (bg == null) {
            // 方式3：使用 window 背景
            bg = UIManager.getColor("window");
        }
        if (bg == null) {
            // 方式4：使用 control 背景
            bg = UIManager.getColor("control");
        }
        if (bg == null) {
            // 最终备选：默认暗色
            return true;
        }
        float[] hsb = Color.RGBtoHSB(bg.getRed(), bg.getGreen(), bg.getBlue(), null);
        return hsb[2] < 0.5f;
    }
}
