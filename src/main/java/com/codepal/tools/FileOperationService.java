package com.codepal.tools;

import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.codepal.utils.FileReaderUtil;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/**
 * 文件操作服务层 —— 统一封装所有文件读写操作
 * <p>
 * 职责：
 * - 统一线程模型（EDT + WriteAction）
 * - 统一错误处理
 * <p>
 * 重要约定（避免 IDE 闪烁）：
 * 所有写操作一律走 VFS（createChildData / setBinaryContent / Document.setText），
 * <b>绝不调用 refreshIoFiles 做磁盘扫描</b>。refreshIoFiles 会让 IDE 重新扫描文件系统并
 * 触发项目树/编辑器重绘，造成整个 IDE 可见闪烁。auto-dev 等同款插件均采用 VFS 直写方式。
 */
public class FileOperationService {

    /**
     * 读取文件内容
     */
    public static String readFile(String filePath, Project project) throws Exception {
        VirtualFile vf = findVirtualFile(filePath, project);
        if (vf == null) {
            throw new Exception("文件不存在: " + filePath);
        }
        if (vf.isDirectory()) {
            throw new Exception("目标是目录: " + filePath);
        }
        String content = FileReaderUtil.readFileContent(vf);
        if (content == null) {
            throw new Exception("无法读取文件: " + filePath);
        }
        return content;
    }

    /**
     * 写入文件内容（自动创建父目录、自动区分新建/覆盖）。
     * <p>
     * - 文件已存在 → 走 {@link #writeFileViaVfs}（已处理“文件已打开则原地改 Document”，无 reload 闪烁）
     * - 文件不存在 → 走 {@link #createNewFile}（VFS createChildData，无 refreshIoFiles 闪烁）
     *
     * @param filePath 文件路径（绝对路径或相对于项目根目录）
     * @param content  要写入的内容
     * @param project  当前项目
     * @return 实际写入的目标文件
     */
    public static File writeFile(String filePath, String content, Project project) throws Exception {
        File targetFile = resolveFilePath(filePath, project);
        if (targetFile.exists()) {
            writeFileViaVfs(filePath, content, project, "CP - AI 写入文件");
            return targetFile;
        }
        return createNewFile(filePath, content, project);
    }

    /**
     * 通过 VFS 写入文件（支持 IDE 撤销/重做）
     * <p>
     * 使用 WriteCommandAction + runWriteAction，写入操作会被 IDE 记录，支持 Ctrl+Z 撤销。
     * 适用于 edit_file / write_file 等需要撤销支持的场景。
     *
     * @param filePath 文件路径
     * @param content  新内容
     * @param project  当前项目
     * @param commandName 命令名称（用于撤销菜单显示）
     * @return VirtualFile 对象
     */
    public static VirtualFile writeFileViaVfs(String filePath, String content, Project project, String commandName) throws Exception {
        VirtualFile vf = findVirtualFile(filePath, project);
        if (vf == null) {
            throw new Exception("文件不存在: " + filePath);
        }
        if (vf.isDirectory()) {
            throw new Exception("目标是目录: " + filePath);
        }
        final VirtualFile finalVf = vf;
        final String finalContent = content != null ? content : "";
        final Project finalProject = project;

        // ── 关键：若文件已在编辑器中打开，直接原地修改其 Document ──
        // 这样 IDEA 在内存中更新编辑器内容，不会触发“外部修改→重新加载编辑器”的闪烁。
        // 之后 saveDocument 把内容写回磁盘（编辑器仍绑定此 Document，不会 reload）。
        Document doc = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(finalVf));
        if (doc != null) {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                LocalHistoryAction lhAction = LocalHistory.getInstance().startAction(commandName);
                try {
                    WriteCommandAction.runWriteCommandAction(finalProject, commandName, null, () -> {
                        ApplicationManager.getApplication().runWriteAction(() -> {
                            doc.setText(finalContent);
                            FileDocumentManager.getInstance().saveDocument(doc);
                        });
                    });
                } finally {
                    lhAction.finish();
                }
            } else {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    LocalHistoryAction lhAction = LocalHistory.getInstance().startAction(commandName);
                    try {
                        WriteCommandAction.runWriteCommandAction(finalProject, commandName, null, () -> {
                            ApplicationManager.getApplication().runWriteAction(() -> {
                                doc.setText(finalContent);
                                FileDocumentManager.getInstance().saveDocument(doc);
                            });
                        });
                    } finally {
                        lhAction.finish();
                    }
                });
            }
            return vf;
        }

        // ── 文件未打开：走 VFS 二进制写入（不影响任何编辑器，无闪烁） ──
        final byte[] contentBytes = finalContent.getBytes(vf.getCharset());
        if (ApplicationManager.getApplication().isDispatchThread()) {
            LocalHistoryAction lhAction = LocalHistory.getInstance().startAction(commandName);
            try {
                WriteCommandAction.runWriteCommandAction(finalProject, commandName, null, () -> {
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            finalVf.setBinaryContent(contentBytes);
                        } catch (Exception e) {
                            throw new RuntimeException("文件写入失败: " + e.getMessage(), e);
                        }
                    });
                });
            } finally {
                lhAction.finish();
            }
        } else {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                LocalHistoryAction lhAction = LocalHistory.getInstance().startAction(commandName);
                try {
                    WriteCommandAction.runWriteCommandAction(finalProject, commandName, null, () -> {
                        ApplicationManager.getApplication().runWriteAction(() -> {
                            try {
                                finalVf.setBinaryContent(contentBytes);
                            } catch (Exception e) {
                                throw new RuntimeException("文件写入失败: " + e.getMessage(), e);
                            }
                        });
                    });
                } finally {
                    lhAction.finish();
                }
            });
        }
        return vf;
    }

    /**
     * 创建新文件（如果已存在则抛异常）。
     * <p>
     * 采用 VFS 直写（auto-dev 同款做法）：{@code dir.createChildData} 在内存中直接注册新文件，
     * 再用 {@code Document.setText} 写入内容并落盘。<b>不调用 refreshIoFiles</b>，从根本上避免
     * 项目树因磁盘扫描而重绘闪烁。
     * <p>
     * 线程安全：内部自动切换到 EDT + WriteCommandAction。
     */
    public static File createNewFile(String filePath, String content, Project project) throws Exception {
        File targetFile = resolveFilePath(filePath, project);
        if (targetFile.exists()) {
            throw new Exception("文件已存在: " + targetFile.getAbsolutePath());
        }
        File parentDir = targetFile.getParentFile();
        if (parentDir == null) {
            throw new Exception("无效路径（缺少父目录）: " + filePath);
        }
        // 确保父目录存在于磁盘（mkdirs 不触发 VFS 刷新，无闪烁）
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new Exception("无法创建父目录: " + parentDir.getAbsolutePath());
        }

        final String fileName = targetFile.getName();
        final String finalContent = content != null ? content : "";
        final Project finalProject = project;

        // 取父目录 VirtualFile；createChildData 会在 VFS 内存中注册新文件，无需扫描磁盘。
        VirtualFile parentVf = resolveDirVf(parentDir, project);
        if (parentVf == null) {
            // 兜底：父目录刚创建尚未入 VFS，做一次精准同步刷新再取（仅限父目录，范围极小）
            LocalFileSystem.getInstance().refreshIoFiles(Collections.singletonList(parentDir), false, false, null);
            parentVf = resolveDirVf(parentDir, project);
        }
        if (parentVf == null) {
            throw new Exception("无法定位父目录 VirtualFile: " + parentDir.getAbsolutePath());
        }

        final VirtualFile finalParent = parentVf;
        final VirtualFile[] created = {null};
        Runnable action = () -> {
            LocalHistoryAction lhAction = LocalHistory.getInstance().startAction("CP - 新建文件");
            try {
                VirtualFile newFile = finalParent.createChildData(finalProject, fileName);
                Document doc = FileDocumentManager.getInstance().getDocument(newFile);
                if (doc != null) {
                    doc.setText(finalContent);
                    FileDocumentManager.getInstance().saveDocument(doc);
                } else {
                    newFile.setBinaryContent(finalContent.getBytes(newFile.getCharset()));
                }
                created[0] = newFile;
            } catch (Exception e) {
                throw new RuntimeException("创建文件失败: " + e.getMessage(), e);
            } finally {
                lhAction.finish();
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            WriteCommandAction.runWriteCommandAction(finalProject, "CP - 新建文件", null, action);
        } else {
            ApplicationManager.getApplication().invokeAndWait(() ->
                    WriteCommandAction.runWriteCommandAction(finalProject, "CP - 新建文件", null, action));
        }
        if (created[0] == null) {
            throw new Exception("文件创建失败（未知原因）");
        }
        return targetFile;
    }

    public static File createDirectory(String dirPath, Project project) throws Exception {
        File targetDir = resolveFilePath(dirPath, project);
        if (targetDir.exists()) {
            if (!targetDir.isDirectory()) {
                throw new Exception("路径已存在但不是目录: " + targetDir.getAbsolutePath());
            }
            return targetDir;
        }
        File parentDir = targetDir.getParentFile();
        final String dirName = targetDir.getName();
        final Project finalProject = project;

        VirtualFile parentVf = parentDir != null ? resolveDirVf(parentDir, project) : project.getBaseDir();
        if (parentVf == null) {
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                throw new Exception("无法创建父目录: " + parentDir.getAbsolutePath());
            }
            if (parentDir != null) {
                LocalFileSystem.getInstance().refreshIoFiles(Collections.singletonList(parentDir), false, false, null);
                parentVf = resolveDirVf(parentDir, project);
            }
        }
        if (parentVf == null) {
            throw new Exception("无法定位父目录 VirtualFile: " + (parentDir != null ? parentDir.getAbsolutePath() : dirPath));
        }

        final VirtualFile finalParent = parentVf;
        final VirtualFile[] created = {null};
        Runnable action = () -> {
            LocalHistoryAction lhAction = LocalHistory.getInstance().startAction("CP - 新建目录");
            try {
                created[0] = finalParent.createChildDirectory(finalProject, dirName);
            } catch (Exception e) {
                throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
            } finally {
                lhAction.finish();
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            WriteCommandAction.runWriteCommandAction(finalProject, "CP - 新建目录", null, action);
        } else {
            ApplicationManager.getApplication().invokeAndWait(() ->
                    WriteCommandAction.runWriteCommandAction(finalProject, "CP - 新建目录", null, action));
        }
        if (created[0] == null) {
            throw new Exception("目录创建失败（未知原因）");
        }
        return targetDir;
    }

    /**
     * 删除文件（用于撤销新建文件）
     * <p>
     * 走 VFS 删除（VirtualFile.delete 递归），不调用 refreshIoFiles 扫描，避免项目树闪烁。
     * 线程安全：内部自动切换到 EDT + WriteCommandAction。
     */
    public static boolean deleteFile(String filePath, Project project) throws Exception {
        return deleteFile(filePath, false, project);
    }

    /**
     * 删除文件或目录
     *
     * @param filePath  文件路径
     * @param recursive 是否递归删除目录（目录非空时必须为 true）
     * @param project   当前项目
     * @return 是否成功删除
     */
    public static boolean deleteFile(String filePath, boolean recursive, Project project) throws Exception {
        File targetFile = resolveFilePath(filePath, project);
        if (!targetFile.exists()) return false;

        VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(targetFile);
        if (vf == null) vf = findVirtualFile(filePath, project);
        if (vf == null) return false;

        final VirtualFile finalVf = vf;
        final boolean[] result = {false};
        Runnable action = () -> {
            LocalHistoryAction lhAction = LocalHistory.getInstance().startAction("CP - 删除文件");
            try {
                // 非递归模式且目录非空 → 拒绝（与原 IO 删除语义一致）
                if (!recursive && finalVf.isDirectory()) {
                    VirtualFile[] children = finalVf.getChildren();
                    if (children != null && children.length > 0) {
                        throw new RuntimeException("目录非空，无法删除。如需删除目录及其内容，请设置 recursive=true。");
                    }
                }
                // 走 VFS 递归删除，无需 refreshIoFiles 扫描，避免项目树闪烁
                deleteRecursivelyVfs(finalVf);
                result[0] = true;
            } catch (Exception e) {
                throw new RuntimeException("删除失败: " + e.getMessage(), e);
            } finally {
                lhAction.finish();
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            WriteCommandAction.runWriteCommandAction(project, "CP - 删除文件", null, action);
        } else {
            ApplicationManager.getApplication().invokeAndWait(() ->
                    WriteCommandAction.runWriteCommandAction(project, "CP - 删除文件", null, action));
        }
        return result[0];
    }

    /**
     * 解析文件路径：支持绝对路径和相对路径（相对于项目根目录）
     */
    public static File resolveFilePath(String filePath, Project project) {
        File targetFile = new File(filePath);
        if (!targetFile.isAbsolute() && project != null && project.getBasePath() != null) {
            targetFile = new File(project.getBasePath(), filePath);
        }
        return targetFile;
    }

    /**
     * 查找 VirtualFile（支持绝对路径、相对路径、文件名搜索）
     */
    public static VirtualFile findVirtualFile(String filePath, Project project) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (file != null && file.exists()) return file;

        if (project != null && project.getBaseDir() != null) {
            file = project.getBaseDir().findFileByRelativePath(filePath);
            if (file != null && file.exists()) return file;
        }

        for (Project p : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (p.getBaseDir() == null) continue;
            VirtualFile found = findByName(p.getBaseDir(), filePath);
            if (found != null) return found;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 通过 VFS 递归删除文件或目录（requestor 传 null 即可）。
     */
    private static void deleteRecursivelyVfs(VirtualFile vf) throws IOException {
        if (vf.isDirectory()) {
            VirtualFile[] children = vf.getChildren();
            if (children != null) {
                for (VirtualFile child : children) {
                    deleteRecursivelyVfs(child);
                }
            }
        }
        vf.delete(null);
    }

    /**
     * 解析目录的 VirtualFile：优先按 IO 文件定位，回退到项目根相对路径。
     */
    private static VirtualFile resolveDirVf(File dir, Project project) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(dir);
        if (vf != null) return vf;
        if (project != null && project.getBaseDir() != null && project.getBasePath() != null) {
            String rel = FileUtil.getRelativePath(project.getBasePath(), dir.getAbsolutePath(), '/');
            if (rel != null) {
                VirtualFile found = project.getBaseDir().findFileByRelativePath(rel);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 在目录树下按文件名查找
     */
    private static VirtualFile findByName(com.intellij.openapi.vfs.VirtualFile dir, String name) {
        if (dir.isDirectory()) {
            for (com.intellij.openapi.vfs.VirtualFile child : dir.getChildren()) {
                if (child.getName().equals(name)) return child;
            }
            for (com.intellij.openapi.vfs.VirtualFile child : dir.getChildren()) {
                if (child.isDirectory()) {
                    if (isSkipDir(child.getName())) continue;
                    VirtualFile found = findByName(child, name);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static boolean isSkipDir(String name) {
        if (name.startsWith(".")) return true;
        String[] skip = {"node_modules", "target", "build", "dist", "out",
                ".git", ".idea", ".gradle", "__pycache__", "vendor"};
        for (String s : skip) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
