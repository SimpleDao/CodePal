package com.codepal.toolwindow;

import com.codepal.model.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * 消息队列：AI 正在回复时把用户输入暂存到这里，stream 结束后按顺序自动发出。
 *
 * <p>设计要点：
 * <ul>
 *   <li>LinkedList 实现 FIFO + 头部快速弹出</li>
 *   <li>每条用 UUID 标识，便于「上移/编辑/删除」按 ID 定位（避免 index 在并发变更时漂移）</li>
 *   <li>携带原始文本 + 图片附件引用（不复制文件；附件仍是磁盘路径，发送时复用）</li>
 *   <li>all operations must run on EDT（队列 UI 与 sendMessage 都在 EDT 调用，符合 Swing 约定）</li>
 * </ul>
 *
 * <p>为何不持久化：消息仅在「本轮 AI 回复中」才有意义，跨会话/重启没有保留价值；
 * 重复发送或丢失的风险高于持久化收益 —— 当前窗口关闭/重开即清空。
 */
public class MessageQueue {

    /**
     * 队列里的一项：用户消息的完整快照（文本 + 附件引用）。
     * <p>createdAt 用于 UI 上做相对时间显示（将来扩展）；当前未使用。
     */
    public static class QueueItem {
        public final String id;
        public String text;
        public List<ChatMessage.Attachment> attachments;
        public final long createdAt;

        public QueueItem(String text, List<ChatMessage.Attachment> attachments) {
            this.id = UUID.randomUUID().toString();
            this.text = text == null ? "" : text;
            // 防御拷贝：避免外部引用变更影响队列快照
            this.attachments = attachments == null
                    ? new ArrayList<>()
                    : new ArrayList<>(attachments);
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final LinkedList<QueueItem> items = new LinkedList<>();

    /** 当前队列里有多少条待发消息。 */
    public synchronized int size() { return items.size(); }

    public synchronized boolean isEmpty() { return items.isEmpty(); }

    /** 把用户当前输入压入队尾。返回被加入的项（含其 ID）。 */
    public synchronized QueueItem enqueue(String text, List<ChatMessage.Attachment> attachments) {
        QueueItem item = new QueueItem(text, attachments);
        items.addLast(item);
        return item;
    }

    /** 取队首项（不移除），预览用。 */
    public synchronized QueueItem peekFirst() {
        return items.peekFirst();
    }

    /** 弹出队首一项，作为下一条待发消息。空队列返回 null。 */
    public synchronized QueueItem popFirst() {
        return items.pollFirst();
    }

    /** 按 ID 移除一项；未找到返回 false。 */
    public synchronized boolean removeById(String id) {
        if (id == null) return false;
        java.util.Iterator<QueueItem> it = items.iterator();
        while (it.hasNext()) {
            if (id.equals(it.next().id)) { it.remove(); return true; }
        }
        return false;
    }

    /** 把 ID 对应的项移到队首；已是队首或未找到返回 false。 */
    public synchronized boolean moveToFirst(String id) {
        if (id == null) return false;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) {
                if (i == 0) return false;
                QueueItem item = items.remove(i);
                items.addFirst(item);
                return true;
            }
        }
        return false;
    }

    /** 按 ID 查一项；返回 null 表示未找到。 */
    public synchronized QueueItem getById(String id) {
        if (id == null) return null;
        for (QueueItem item : items) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    /** 当前所有项的快照（用于 UI 渲染；返回 ArrayList 以防外部修改影响内部 LinkedList）。 */
    public synchronized List<QueueItem> snapshot() {
        return new ArrayList<>(items);
    }

    /** 清空队列（一般用于 reset / dispose）。 */
    public synchronized void clear() {
        items.clear();
    }
}
