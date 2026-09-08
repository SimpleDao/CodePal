package com.codepal.tools;

import java.util.*;

/**
 * 内存工具关键词索引，基于 BM25 加权检索。
 *
 * <p>BM25 相比 TF-IDF 的核心改进：
 * <ul>
 *   <li><b>TF 饱和曲线</b>：词频从 1→2 权重增幅大，从 10→11 几乎不变，防止高频词绑架排序</li>
 *   <li><b>文档长度归一化</b>：长工具描述不会被过度匹配惩罚（b=0.75 中等归一化）</li>
 * </ul>
 *
 * <p>中文支持：字符二元组（bigram）分词，"搜索代码" → ["搜索","索代","代码"]
 * 英文支持：按标点 + 空格 + 驼峰拆分分词
 *
 * @author 水龙吟
 */
public class ToolIndex {

    private static final double K1 = 1.2;  // TF 饱和度
    private static final double B  = 0.75; // 文档长度归一化强度

    /** 工具名 → { 词项 → 词频 } */
    private final Map<String, Map<String, Integer>> tfIndex = new LinkedHashMap<>();

    /** 词项 → 出现该词项的工具数（DF） */
    private final Map<String, Integer> dfIndex = new HashMap<>();

    /** 工具名 → 文档总词数 */
    private final Map<String, Integer> docLengths = new LinkedHashMap<>();

    /** 工具名 → 描述文本 */
    private final Map<String, String> descriptionIndex = new LinkedHashMap<>();

    /** 已注册工具总数 */
    private int totalDocs = 0;

    /**
     * 注册工具及其文本语料（name + keywords + description 合并为文档）。
     */
    public void register(String name, Set<String> keywords, String desc) {
        // 构建文档文本：工具名 + 驼峰拆分 + 关键词 + 描述
        StringBuilder doc = new StringBuilder();
        doc.append(name.toLowerCase()).append(' ');

        for (String part : name.split("[_/.-]")) {
            doc.append(part.toLowerCase()).append(' ');
            for (String cp : splitCamel(part)) doc.append(cp.toLowerCase()).append(' ');
        }

        for (String kw : keywords) doc.append(kw).append(' ');
        if (desc != null && !desc.isEmpty()) doc.append(desc.toLowerCase());

        // 分词 → 词频 Map
        Map<String, Integer> tfMap = tokenize(doc.toString());
        tfIndex.put(name, tfMap);

        // 记录文档长度（总词数）
        int docLen = tfMap.values().stream().mapToInt(Integer::intValue).sum();
        docLengths.put(name, docLen);

        totalDocs++;

        // 更新文档频率（每个词项只计一次 per 工具）
        for (String term : tfMap.keySet()) {
            dfIndex.merge(term, 1, Integer::sum);
        }

        descriptionIndex.put(name, desc != null ? desc : name);
    }

    /**
     * BM25 检索。
     *
     * @param query      用户查询
     * @param maxResults 最多返回数
     * @return 匹配的工具名列表，按 BM25 得分降序
     */
    public List<String> search(String query, int maxResults) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        Map<String, Integer> queryTerms = tokenize(query.toLowerCase());
        if (queryTerms.isEmpty()) return Collections.emptyList();

        // 平均文档长度
        double avgDocLen = totalDocs > 0
                ? (double) docLengths.values().stream().mapToInt(Integer::intValue).sum() / totalDocs
                : 1.0;

        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : tfIndex.entrySet()) {
            String toolName = entry.getKey();
            Map<String, Integer> tfMap = entry.getValue();
            int docLen = docLengths.get(toolName);
            double score = 0.0;

            for (Map.Entry<String, Integer> qt : queryTerms.entrySet()) {
                String term = qt.getKey();
                int tf = tfMap.getOrDefault(term, 0);
                if (tf == 0) continue;

                // Robertson-Spärck Jones IDF（BM25 标准 IDF）
                int df = dfIndex.getOrDefault(term, 0);
                double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);

                // BM25 TF 饱和 + 文档长度归一化
                double lenNorm = 1.0 - B + B * docLen / avgDocLen;
                double tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * lenNorm);

                score += idf * tfNorm;
            }

            if (score > 0) scores.put(toolName, score);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 获取工具描述 */
    public String getDescription(String name) {
        return descriptionIndex.getOrDefault(name, name);
    }

    /** 已注册工具总数 */
    public int size() {
        return totalDocs;
    }

    // ── 内部分词 ──

    /**
     * 分词并返回词频 Map。
     * 英文按标点/空格切分，中文用二元组 + 单字。
     */
    private static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();

        String[] tokens = text.split("[^a-z0-9\\u4e00-\\u9fff]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (isChinese(token)) {
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    if (c >= 0x4e00 && c <= 0x9fff) {
                        freq.merge(String.valueOf(c), 1, Integer::sum);
                        if (i + 1 < token.length() && token.charAt(i + 1) >= 0x4e00) {
                            freq.merge(token.substring(i, i + 2), 1, Integer::sum);
                        }
                    }
                }
            } else {
                if (token.length() >= 2) freq.merge(token, 1, Integer::sum);
            }
        }
        return freq;
    }

    private static boolean isChinese(String s) {
        return s.codePointAt(0) >= 0x4e00 && s.codePointAt(0) <= 0x9fff;
    }

    private static List<String> splitCamel(String s) {
        return Arrays.asList(s.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"));
    }
}
