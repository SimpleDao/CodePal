package com.codepal.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/**
 * AI 消息渲染组件：封装 JBCefBrowser（Chromium 内核），
 * 当 JCEF 不支持时自动回退到精简 CSS 的 JEditorPane。
 * <p>
 * 兼容 IDEA 2026.1+（intellij-platform 2.16.0），
 * 不依赖任何 org.cef.* 底层 API，在 Linux 无图形界面或远程开发环境自动降级。
 */
public final class AiMessageBrowser {

    private final String rawMarkdown;
    private final JComponent component;
    private JBCefBrowserBase browser;
    private JEditorPane fallbackPane;
    private JBCefJSQuery copyQuery;
    private JBCefJSQuery heightQuery;

    /**
     * @param rawMarkdown 原始 Markdown 文本（用于"复制原始内容"）
     * @param html        已渲染的完整 HTML 字符串
     * @param bubbleBg    气泡背景色
     */
    public AiMessageBrowser(String rawMarkdown, String html, Color bubbleBg) {
        this.rawMarkdown = rawMarkdown;

        if (JBCefApp.isSupported()) {
            this.component = createCefComponent(html, bubbleBg);
        } else {
            this.component = createFallbackComponent(html, bubbleBg);
        }
    }

    /**
     * 获取 Swing 组件，用于添加到面板中。
     */
    public JComponent getComponent() {
        return component;
    }

    /**
     * 释放 JBCefBrowser 资源（必须在面板移除时调用）。
     */
    public void dispose() {
        if (browser != null) {
            try {
                browser.dispose();
            } catch (Exception ignored) {
            }
            browser = null;
        }
        if (copyQuery != null) {
            try {
                copyQuery.dispose();
            } catch (Exception ignored) {
            }
            copyQuery = null;
        }
        if (heightQuery != null) {
            try {
                heightQuery.dispose();
            } catch (Exception ignored) {
            }
            heightQuery = null;
        }
    }

    /**
     * 获取原始 Markdown 文本。
     */
    public String getRawMarkdown() {
        return rawMarkdown;
    }

    // ── JBCefBrowser 模式 ──

    private JComponent createCefComponent(String html, Color bubbleBg) {
        browser = new JBCefBrowser();

        // 注入 JS 通信通道：前端调用 window.intellijCopyMarkdown() 触发 Java 回调
        copyQuery = JBCefJSQuery.create(browser);
        copyQuery.addHandler((String ignored) -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(rawMarkdown), null);
            return null;
        });

        // 注入高度通信通道：前端报告内容高度，Java 端设置组件最大高度并触发滚动
        heightQuery = JBCefJSQuery.create(browser);
        // wrapper 引用将在后面赋值，使用数组作为可变闭包变量
        final JBPanel<?>[] wrapperRef = new JBPanel<?>[1];
        heightQuery.addHandler((String heightStr) -> {
            try {
                int h = Integer.parseInt(heightStr);
                if (h > 0 && wrapperRef[0] != null) {
                    final int finalH = h + 12; // 额外留 12px 余量防止截断
                    SwingUtilities.invokeLater(() -> {
                        wrapperRef[0].setPreferredSize(new Dimension(wrapperRef[0].getPreferredSize().width, finalH));
                        wrapperRef[0].setMaximumSize(new Dimension(Integer.MAX_VALUE, finalH));
                        wrapperRef[0].revalidate();
                        wrapperRef[0].repaint();
                        // 逐级向上触发父容器 revalidate，直到 JBScrollPane 重新计算滚动范围
                        Container parent = wrapperRef[0].getParent();
                        while (parent != null) {
                            parent.revalidate();
                            parent.repaint();
                            if (parent instanceof JScrollPane) {
                                // 找到包裹的滚动面板，直接滚动到底部
                                JScrollPane scrollPane = (JScrollPane) parent;
                                SwingUtilities.invokeLater(() -> {
                                    JScrollBar vbar = scrollPane.getVerticalScrollBar();
                                    vbar.setValue(vbar.getMaximum());
                                });
                                break;
                            }
                            parent = parent.getParent();
                        }
                    });
                }
            } catch (NumberFormatException ignored) {
            }
            return null;
        });

        String wrappedHtml = wrapHtmlForCef(html, copyQuery, heightQuery);

        // 关键：JBCefBrowser 会覆盖 getPreferredSize() 返回巨大值，BoxLayout 会据此拉伸
        // 解决方案：用容器包装，容器覆盖 getPreferredSize() 返回受控值
        final JComponent browserComp = browser.getComponent();
        browserComp.setBackground(bubbleBg);
        browserComp.setOpaque(true);
        browserComp.setMinimumSize(new Dimension(80, 24));

        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                // JBCefBrowser 的默认 preferredSize 可能返回巨大值（如 800x600）
                // 初始高度限制为 300px（而非之前的 100），减少内容被截断的概率
                // JS 的 MutationObserver 会在内容渲染完成后立即上报真实高度
                if (d.height > 300) {
                    d.height = 300;
                }
                return d;
            }

            @Override
            public Dimension getMaximumSize() {
                // 返回 preferredSize，这样当 JS 回调更新 preferredSize 后，
                // maximumSize 也会自动跟随更新
                return getPreferredSize();
            }
        };
        wrapperRef[0] = wrapper;
        wrapper.setOpaque(false);
        wrapper.add(browserComp, BorderLayout.NORTH);

        browser.loadHTML(wrappedHtml);

        return wrapper;
    }

    /**
     * 在 HTML 中注入：
     * 1. 自定义右键菜单（CSS + DOM + JS）——不依赖任何底层 CEF API
     * 2. JS Bridge 函数，用于"复制原始 Markdown"
     * 3. 自动报告内容高度给 Java 端
     */
    private String wrapHtmlForCef(String html, JBCefJSQuery copyQuery, JBCefJSQuery heightQuery) {
        String jsBridge = copyQuery.inject("window.intellijCopyMarkdown");
        String heightBridge = heightQuery.inject("window.reportHeight");

        // 右键菜单样式（暗色主题风格，适配 IDEA Darcula）
        String extraCss =
                "<style>" +
                        "#lc-ctx-menu{position:fixed;display:none;z-index:2147483647;" +
                        "background:#2b2b2b;border:1px solid #4a4a4a;border-radius:4px;" +
                        "padding:4px 0;font-family:system-ui,sans-serif;font-size:13px;" +
                        "color:#bbb;box-shadow:0 2px 8px rgba(0,0,0,0.3);min-width:160px}" +
                        "#lc-ctx-menu div{padding:6px 16px;cursor:pointer;white-space:nowrap}" +
                        "#lc-ctx-menu div:hover{background:#3c3f41;color:#fff}" +
                        "</style>";

        // 右键菜单 DOM
        String menuHtml =
                "<div id='lc-ctx-menu'>" +
                        "<div onclick='lcCopySel()'>复制</div>" +
                        "<div onclick='lcSelectAll()'>全选</div>" +
                        "<div onclick='window.intellijCopyMarkdown()'>复制原始内容</div>" +
                        "</div>";

        // 右键菜单 JS + bridge + 自动报告高度
        String script =
                "<script type='text/javascript'>" +
                        "function lcCopySel(){" +
                        "var t=window.getSelection().toString();" +
                        "if(t&&navigator.clipboard)navigator.clipboard.writeText(t);" +
                        "document.getElementById('lc-ctx-menu').style.display='none';" +
                        "}" +
                        "function lcSelectAll(){" +
                        "var r=document.createRange();r.selectNodeContents(document.body);" +
                        "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
                        "}" +
                        "document.addEventListener('contextmenu',function(e){" +
                        "e.preventDefault();var m=document.getElementById('lc-ctx-menu');" +
                        "m.style.display='block';m.style.left=e.clientX+'px';m.style.top=e.clientY+'px';" +
                        "});" +
                        "document.addEventListener('click',function(){" +
                        "document.getElementById('lc-ctx-menu').style.display='none';" +
                        "});" +
                        // 使用 MutationObserver 监听 DOM 变化，内容渲染完成后立即报告高度
                        "var lastH=0;" +
                        "var obs=new MutationObserver(function(){" +
                        "var h=document.body.scrollHeight;" +
                        "if(h!==lastH&&window.reportHeight){" +
                        "lastH=h;window.reportHeight(String(h));" +
                        "}" +
                        "});" +
                        "obs.observe(document.body,{childList:true,subtree:true,characterData:true});" +
                        // 初始报告一次
                        "if(window.reportHeight)window.reportHeight(String(document.body.scrollHeight));" +
                        jsBridge +
                        heightBridge +
                        "</script>";

        String result = html;
        result = result.replace("</head>", extraCss + "</head>");
        result = result.replace("</body>", menuHtml + script + "</body>");
        return result;
    }

    // ── JEditorPane 回退模式 ──

    private JComponent createFallbackComponent(String html, Color bubbleBg) {
        String safeHtml = stripUnsafeCss(html);

        fallbackPane = new JEditorPane("text/html", safeHtml);
        fallbackPane.setEditable(false);
        fallbackPane.setOpaque(true);
        fallbackPane.setBackground(bubbleBg);
        fallbackPane.setForeground(UIUtil.getLabelForeground());
        fallbackPane.setBorder(JBUI.Borders.empty(10, 14));
        fallbackPane.setCaretPosition(0);
        fallbackPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        installFallbackPopupMenu();

        return fallbackPane;
    }

    private void installFallbackPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(JBUI.Borders.customLine(JBColor.namedColor("Popup.borderColor", JBColor.border()), 1));
        menu.setBackground(UIUtil.getPanelBackground());

        JMenuItem copySelItem = new JMenuItem("复制选中");
        copySelItem.setFont(JBUI.Fonts.label(12));
        copySelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copySelItem.addActionListener(e -> {
            String sel = fallbackPane.getSelectedText();
            if (sel != null && !sel.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sel), null);
            }
        });
        menu.add(copySelItem);

        JMenuItem copyMdItem = new JMenuItem("复制原始内容（Markdown）");
        copyMdItem.setFont(JBUI.Fonts.label(12));
        copyMdItem.addActionListener(e ->
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(rawMarkdown), null));
        menu.add(copyMdItem);

        JMenuItem selectAllItem = new JMenuItem("全选");
        selectAllItem.setFont(JBUI.Fonts.label(12));
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        selectAllItem.addActionListener(e -> fallbackPane.selectAll());
        menu.add(selectAllItem);

        fallbackPane.setComponentPopupMenu(menu);
    }

    /**
     * 将 HTML 中的 Swing CSS 不支持的属性移除，用于 JEditorPane 回退模式。
     */
    private static String stripUnsafeCss(String html) {
        if (html == null) return "";
        String result = html;
        result = result.replaceAll("\\s*box-sizing\\s*:[^;]+;?\\s*", "");
        result = result.replaceAll("\\s*word-break\\s*:[^;]+;?\\s*", "");
        result = result.replaceAll("\\s*border-radius\\s*:[^;]+;?\\s*", "");
        result = result.replaceAll("\\s*overflow-x\\s*:[^;]+;?\\s*", "");
        result = result.replaceAll("\\s*opacity\\s*:[^;]+;?\\s*", "");
        result = result.replaceAll("\\s*p:last-child\\s*\\{[^}]*\\}\\s*", "");
        result = result.replaceAll("\\s*a:hover\\s*\\{[^}]*\\}\\s*", "");
        result = result.replaceAll("\\s*\\*\\s*\\{[^}]*\\}\\s*", "");
        return result;
    }
}
