package com.codepal.cc.acp.model;

/**
 * ACP ContentBlock — 构成 prompt 消息的基本单元。
 */
public class ContentBlock {

    private String type;
    private String text;
    private ResourceContent resource;

    public ContentBlock() {}

    public static ContentBlock text(String text) {
        ContentBlock block = new ContentBlock();
        block.type = "text";
        block.text = text;
        return block;
    }

    public static ContentBlock resource(String uri, String text, String mimeType) {
        ContentBlock block = new ContentBlock();
        block.type = "resource";
        block.resource = new ResourceContent();
        block.resource.uri = uri;
        block.resource.text = text;
        block.resource.mimeType = mimeType;
        return block;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public ResourceContent getResource() { return resource; }
    public void setResource(ResourceContent resource) { this.resource = resource; }

    public static class ResourceContent {
        private String uri;
        private String text;
        private String mimeType;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    }
}
