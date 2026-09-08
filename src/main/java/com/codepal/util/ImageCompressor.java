package com.codepal.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * 发送前图片压缩：缩放最长边 + JPEG 质量 + 超限迭代降级，避免把整张大图以 base64 塞进请求体。
 *
 * <p>思路参考 auto-dev 的 {@code ImageCompressor}（https://github.com/unit-mesh/auto-dev）：
 * 先按最长边缩放到 maxDim 内，再以 JPEG 质量压缩；若仍超出 maxFileSize，逐步降质量，
 * 最后还不够则等比进一步缩小。纯 AWT/ImageIO，无第三方依赖。
 */
public final class ImageCompressor {

    /** 压缩配置（BALANCED 预设：最长边 1024 / JPEG 质量 0.8 / 上限 500KB） */
    public static class Config {
        public int maxDim = 1024;          // 最长边像素上限
        public float quality = 0.8f;       // JPEG 质量 0..1
        public long maxFileSize = 500 * 1024; // 压缩后字节上限
        public boolean useJpeg = true;     // false 则保留 PNG（不压质量，仅缩放）

        public static final Config BALANCED = new Config();
    }

    private ImageCompressor() {}

    public static byte[] compress(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IOException("无法读取图片: " + file.getAbsolutePath());
        return compressImage(img, Config.BALANCED);
    }

    public static byte[] compress(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("无法从字节读取图片");
        return compressImage(img, Config.BALANCED);
    }

    private static byte[] compressImage(BufferedImage src, Config cfg) throws IOException {
        int ow = src.getWidth(), oh = src.getHeight();
        int[] dim = fitWithin(ow, oh, cfg.maxDim);
        int tw = dim[0], th = dim[1];

        BufferedImage resized = (tw != ow || th != oh) ? resize(src, tw, th) : src;

        float q = cfg.quality;
        byte[] out = toBytes(resized, q, cfg.useJpeg);

        // 迭代降质量直到达标（下限 0.3）
        while (out.length > cfg.maxFileSize && q > 0.3f) {
            q -= 0.1f;
            out = toBytes(resized, q, cfg.useJpeg);
        }

        // 仍超限：按 sqrt(上限/当前) 进一步缩小（最小 100px）
        if (out.length > cfg.maxFileSize) {
            double factor = Math.sqrt((double) cfg.maxFileSize / out.length);
            int nw = Math.max(100, (int) (tw * factor));
            int nh = Math.max(100, (int) (th * factor));
            BufferedImage smaller = resize(resized, nw, nh);
            out = toBytes(smaller, q, cfg.useJpeg);
        }
        return out;
    }

    private static int[] fitWithin(int w, int h, int max) {
        if (w <= max && h <= max) return new int[]{w, h};
        double ratio = (w >= h) ? (double) max / w : (double) max / h;
        return new int[]{(int) (w * ratio), (int) (h * ratio)};
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] toBytes(BufferedImage img, float quality, boolean jpeg) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (jpeg) {
            // JPEG 不支持透明：有 alpha 则填白底转 RGB
            BufferedImage rgb = (img.getColorModel().hasAlpha())
                    ? toRgb(img) : img;
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(ImageIO.createImageOutputStream(baos));
            writer.write(null, new IIOImage(rgb, null, null), param);
            writer.dispose();
        } else {
            ImageIO.write(img, "png", baos);
        }
        return baos.toByteArray();
    }

    private static BufferedImage toRgb(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /** 压缩后 MIME（JPEG 压缩后为 image/jpeg） */
    public static String compressedMime(Config cfg) {
        return cfg.useJpeg ? "image/jpeg" : "image/png";
    }
}
