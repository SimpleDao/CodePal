package com.codepal.tools;

/**
 * ACP Agent 工具数据模型
 */
public class AcpAgentTool {

    private String name;
    private String version;
    private String description;
    private String author;
    private boolean bundled;
    private boolean installed;
    private String iconType;

    public AcpAgentTool(String name, String version, String description,
                        String author, boolean bundled, boolean installed, String iconType) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.author = author;
        this.bundled = bundled;
        this.installed = installed;
        this.iconType = iconType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public boolean isBundled() { return bundled; }
    public void setBundled(boolean bundled) { this.bundled = bundled; }

    public boolean isInstalled() { return installed; }
    public void setInstalled(boolean installed) { this.installed = installed; }

    public String getIconType() { return iconType; }
    public void setIconType(String iconType) { this.iconType = iconType; }

    public String getDisplayName() {
        return name + (bundled ? " （内置）" : "");
    }

    @Override
    public String toString() {
        return name + " v" + version;
    }
}

