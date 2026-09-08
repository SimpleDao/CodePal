package com.codepal.skills;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 技能存储 —— 管理技能文档的物理位置。
 *
 * <p>设计参照本插件数据库的做法（{@code SqliteDatabaseManager} 把 chat_history.db 放在
 * {@code PathManager.getSystemPath()/CPPlugin/db}）：技能文档也放在用户机器上的
 * <b>可写目录</b> 而非 jar 内的 resources，这样用户可自行修改技能内容而无需重新打包插件。
 *
 * <p>resources 内的 {@code skills/*.md} 仅作为「出厂默认」：首次需要时拷贝到用户目录；
 * 若用户已修改过用户目录的文件，则不覆盖。读取时优先用户目录，缺失再回退内置资源。
 */
public class SkillStore {

    private static final Logger LOG = Logger.getInstance(SkillStore.class);

    private static final String PLUGIN_DIR = "CPPlugin";
    private static final String SKILLS_SUBDIR = "skills";
    private static final String RESOURCE_ROOT = "skills/";

    /** 文件夹型 skill 的入口文件名（用户从系统文件选择器导入的目录内必须含此文件） */
    private static final String FOLDER_SKILL_FILE = "SKILL.md";

    /** 单文件型 skill（旧格式，向后兼容） */
    private static final String FLAT_SKILL_SUFFIX = ".md";

    /** 用户机器上的技能目录：{@code <IDE system path>/CPPlugin/skills} */
    public static Path getUserSkillsDir() {
        return Paths.get(PathManager.getSystemPath(), PLUGIN_DIR, SKILLS_SUBDIR);
    }

    public static Path getUserSkillPath(String name) {
        return getUserSkillsDir().resolve(name + ".md");
    }

    /** 若用户机器上不存在该技能文件，则从插件内置资源拷贝出厂默认（不覆盖已有） */
    public static void ensureDefault(String name) {
        Path userPath = getUserSkillPath(name);
        if (Files.exists(userPath)) return;
        try (InputStream is = SkillStore.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + name + ".md")) {
            if (is == null) return;
            Files.createDirectories(userPath.getParent());
            Files.copy(is, userPath);
        } catch (IOException ignored) {
            // 拷贝失败不影响后续：readSkill 会回退到内置资源读取
        }
    }

    /** 读取技能全文：优先用户机器文件，缺失/读取失败时回退内置资源
     *  兼容两种形式：
     *  1) 文件夹型：{@code <user>/skills/<name>/SKILL.md}
     *  2) 单文件型（旧）：{@code <user>/skills/<name>.md}
     *  都没有再回退内置资源 skills/<name>.md
     *  <p>读取统一走 {@link #readFileLenient}：对含非法 UTF-8 字节的文件（如 GBK 编码的
     *  破折号/箭头符号）做容错替换，而不是直接抛 MalformedInputException 导致整个 skill 加载失败。 */
    @Nullable
    public static String readSkill(String name) {
        ensureDefault(name);
        // 1) 文件夹型
        Path folder = getUserSkillsDir().resolve(name);
        Path skillMd = folder.resolve(FOLDER_SKILL_FILE);
        if (Files.isDirectory(folder) && Files.isRegularFile(skillMd)) {
            return readFileLenient(skillMd);
        }
        // 2) 单文件型（旧）
        Path flat = getUserSkillPath(name);
        if (Files.isRegularFile(flat)) {
            return readFileLenient(flat);
        }
        // 3) 内置资源回退（jar 内资源按字节读，不做编码容错——出厂默认保证是合法 UTF-8）
        try (InputStream is = SkillStore.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + name + FLAT_SKILL_SUFFIX)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
        return null;
    }

    /** 容错读文本文件：先按 UTF-8 读；若文件含非法 UTF-8 字节序列（MalformedInputException），
     *  降级为 ISO-8859-1 逐字节解码——保证任何字节都能读出内容，只是非 ASCII 字符可能显示为乱码，
     *  但不会让整个 skill 加载失败。 */
    private static String readFileLenient(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException mie) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                LOG.warn("readFileLenient: " + file + " 含非法 UTF-8 字节，已降级为 ISO-8859-1 读取（非 ASCII 字符可能乱码但不影响加载）");
                return new String(bytes, StandardCharsets.ISO_8859_1);
            } catch (IOException ioe) {
                LOG.warn("readFileLenient fallback failed: " + file, ioe);
                return null;
            }
        } catch (IOException e) {
            LOG.warn("readFileLenient failed: " + file, e);
            return null;
        }
    }

    /** 摘要最大字符数：只取 SKILL.md 头部简介段，避免整篇注入占用上下文 */
    private static final int SUMMARY_MAX_CHARS = 600;

    /**
     * 读取技能「摘要」：只取文件开头到第一个二级/三级标题或分隔线为止的部分（标题 + 简介段），
     * 并限制在 SUMMARY_MAX_CHARS 内。用于勾选后注入系统提示词——模型知道有这个技能及其用途，
     * 需要细节时再通过 load_skill 拉全文。读取来源与 readSkill 一致（用户目录 → 内置资源）。
     */
    @Nullable
    public static String readSkillSummary(String name) {
        String full = readSkill(name);
        if (full == null || full.isBlank()) return null;
        // 找第一个章节分隔点（## / ### / --- 行），摘要到此为止
        int cut = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(#{2,3}\\s|\\s*---\\s*$)").matcher(full);
        if (m.find()) cut = m.start();
        String summary = (cut > 0) ? full.substring(0, cut) : full;
        summary = summary.trim();
        if (summary.length() > SUMMARY_MAX_CHARS) {
            summary = summary.substring(0, SUMMARY_MAX_CHARS).trim();
        }
        return summary;
    }

    /**
     * 列出当前可用技能名（不含 .md 后缀），供 load_skill(list=true) 发现。
     * 合并用户目录已有技能 + 内置出厂默认技能（去重），确保首次使用前也能发现。
     */
    public static List<String> listSkills() {
        Set<String> names = new HashSet<>();

        // 1) 用户目录中已有的 .md（单文件型，向后兼容）
        Path dir = getUserSkillsDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                // a) 文件夹型 skill：子目录里含 SKILL.md 才算
                stream.filter(Files::isDirectory)
                        .filter(p -> Files.isRegularFile(p.resolve(FOLDER_SKILL_FILE)))
                        .map(p -> p.getFileName().toString())
                        .forEach(names::add);
                // b) 单文件型（旧）
                try (Stream<Path> flatStream = Files.list(dir)) {
                    flatStream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(FLAT_SKILL_SUFFIX))
                            .map(p -> {
                                String n = p.getFileName().toString();
                                return n.substring(0, n.length() - FLAT_SKILL_SUFFIX.length());
                            })
                            .forEach(names::add);
                }
            } catch (IOException ignored) { }
        }

        // 2) 内置 resources 中的出厂默认（即使用户目录还没拷贝也要能发现）
        try {
            Enumeration<URL> entries = SkillStore.class.getClassLoader()
                    .getResources(RESOURCE_ROOT);
            while (entries.hasMoreElements()) {
                URL root = entries.nextElement();
                // jar 内资源用 JarURLConnection / 文件系统用 File
                String proto = root.getProtocol();
                if ("jar".equals(proto)) {
                    // 从 jar URL 提取 skill 名：遍历 jar 内 skills/ 下条目
                    java.net.JarURLConnection conn =
                            (java.net.JarURLConnection) root.openConnection();
                    try (java.util.jar.JarFile jar = conn.getJarFile()) {
                        jar.stream()
                                .filter(e -> e.getName().startsWith("skills/")
                                        && e.getName().endsWith(".md")
                                        && !e.isDirectory())
                                .map(e -> {
                                    String n = e.getName();
                                    return n.substring(n.lastIndexOf('/') + 1)
                                            .replaceAll("\\.md$", "");
                                })
                                .forEach(names::add);
                    }
                } else if ("file".equals(proto)) {
                    // 开发模式 / 解压后的 classes 目录
                    Path rootPath = Paths.get(root.toURI());
                    if (Files.isDirectory(rootPath)) {
                        try (var stream = Files.list(rootPath)) {
                            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                                    .map(p -> p.getFileName().toString()
                                            .replaceAll("\\.md$", ""))
                                    .forEach(names::add);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 扫描内置失败不影响：至少返回用户目录的
        }

        List<String> result = new ArrayList<>(names);
        Collections.sort(result);
        return result;
    }

    /**
     * 从用户机器导入一个 skill 文件夹。
     * 要求文件夹根目录必须含 {@value #FOLDER_SKILL_FILE} 文件，
     * 整个目录树拷贝到 {@code <user>/skills/<folder.name>/}。
     *
     * @return 导入后使用的 skill 名（即文件夹名）；若已存在同名则返回已存在的并跳过
     * @throws IOException 文件夹无效或拷贝失败
     */
    public static String importSkillFolder(Path sourceDir) throws IOException {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            throw new IOException("请选择一个有效的文件夹（含 SKILL.md）");
        }
        Path skillMd = sourceDir.resolve(FOLDER_SKILL_FILE);
        if (!Files.isRegularFile(skillMd)) {
            throw new IOException("所选文件夹必须包含 " + FOLDER_SKILL_FILE + " 文件");
        }
        String name = sourceDir.getFileName().toString();
        Path target = getUserSkillsDir().resolve(name);
        if (Files.exists(target)) {
            // 已在 → 覆盖更新（清掉旧的再拷）
            deleteRecursively(target);
        }
        Files.createDirectories(getUserSkillsDir());
        copyRecursively(sourceDir, target);
        return name;
    }

    /**
     * 删除一个已导入的 skill（仅限用户目录下的；不删内置出厂默认）。
     * @return true 表示成功删除；false 表示不存在或是出厂默认（不可删）
     */
    public static boolean removeSkill(String name) {
        if (name == null || name.isBlank()) return false;
        Path userDir = getUserSkillsDir();
        // 文件夹型
        Path folder = userDir.resolve(name);
        if (Files.isDirectory(folder)) {
            return deleteRecursively(folder);
        }
        // 单文件型
        Path flat = userDir.resolve(name + FLAT_SKILL_SUFFIX);
        if (Files.isRegularFile(flat)) {
            try {
                Files.delete(flat);
                return true;
            } catch (IOException e) {
                LOG.warn("removeSkill failed: " + flat, e);
            }
        }
        return false;
    }

    /** 检查 skill 是否由用户导入（可删除）—— 不在内置出厂默认集合里 */
    public static boolean isUserImported(String name) {
        if (name == null) return false;
        Path userDir = getUserSkillsDir();
        return Files.isDirectory(userDir.resolve(name))
                || Files.isRegularFile(userDir.resolve(name + FLAT_SKILL_SUFFIX));
    }

    // ──────────────── 文件夹操作工具 ────────────────

    private static boolean deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return false;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            LOG.warn("deleteRecursively failed: " + root, e);
            return false;
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
