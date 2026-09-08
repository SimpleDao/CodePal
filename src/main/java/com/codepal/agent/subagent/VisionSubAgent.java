package com.codepal.agent.subagent;

import com.intellij.openapi.project.Project;
import com.codepal.api.VisionClient;

import java.util.Map;

/**
 * 视觉子智能体 —— 调用独立配置的视觉模型理解图片，返回文字描述/答案。
 *
 * <p>与 SearchSubAgent 对齐：主模型（可能无视觉能力）通过 view_image 工具把图片交给
 * 本子智能体，由独立的视觉模型完成分析，只把精简的文字结果返回主模型，
 * 不把图片字节/多轮分析塞进主对话上下文。
 *
 * @author CP Multi-Agent
 */
public class VisionSubAgent extends SubAgent<VisionSubAgent.VisionTask, AgentResult> {

    private static final String AGENT_NAME = "vision-agent";
    private static final String AGENT_DESC = "视觉子智能体，调用独立视觉模型分析图片，返回文字描述或问题答案";

    public VisionSubAgent(Project project) {
        super(AGENT_NAME, AGENT_DESC, project);
        this.priority = 90;
    }

    @Override
    public boolean shouldTrigger(Map<String, Object> context) {
        if (context == null) return false;
        Object path = context.get("imagePath");
        return path != null && !path.toString().isBlank();
    }

    @Override
    public AgentResult execute(VisionTask task, ProgressCallback callback) {
        if (task == null || task.imagePath == null || task.imagePath.isBlank()) {
            return AgentResult.error("视觉任务缺少图片路径 (image_path)");
        }
        try {
            if (callback != null) {
                callback.onProgress("视觉子智能体正在分析图片：" + task.imagePath);
            }
            VisionClient client = new VisionClient();
            String desc = client.describe(task.imagePath,
                    task.mimeType != null ? task.mimeType : "",
                    task.question != null ? task.question : "");
            if (desc == null || desc.isEmpty()) {
                return AgentResult.error("视觉模型未返回任何内容");
            }
            return new AgentResult.Builder()
                    .success(true)
                    .content("【视觉子智能体返回的图片内容】\n" + desc)
                    .addMetadata("imagePath", task.imagePath)
                    .build();
        } catch (Exception e) {
            return AgentResult.error("查看图片失败：" + e.getMessage());
        }
    }

    /** 视觉子智能体输入任务（图片路径 + 可选 MIME/问题） */
    public static class VisionTask {
        public final String imagePath;
        public final String mimeType;
        public final String question;

        public VisionTask(String imagePath, String mimeType, String question) {
            this.imagePath = imagePath;
            this.mimeType = mimeType;
            this.question = question;
        }
    }
}
