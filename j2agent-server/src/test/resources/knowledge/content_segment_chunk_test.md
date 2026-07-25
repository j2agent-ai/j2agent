# 分片切窗测试文档

本文件用于验证入库 `content_segment` 与超长 query 切分共用 `j2agent.knowledge.repo` 的窗口与重叠配置。

## 使用方式

1. 将本文件放入已在“知识库列表”中配置的知识库目录（默认 `metadata_config.minHeadingLevel=3`）。
2. 确认 `application.yaml` 中 `content-segment-chars: 2000`、`content-segment-overlap-chars: 200`。
3. 触发 sync 或完全重建后，在 Milvus / 日志中检查向量条数。

### 短正文块

短于一个窗口（2000 字符），预期：**1 条 title + 1 条 content_segment**。

### 长正文块

正文约 6300 字符，在窗口 2000、重叠 200 配置下预期：**1 条 title + 4 条 content_segment**。

本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。
本段用于验证 content_segment 按 content-segment-chars 与 content-segment-overlap-chars 配置的滑动窗口切分。

### 空正文标题块

### 预期向量汇总

| 标题块 | text_chunk | Milvus 向量 |
|--------|------------|-------------|
| 短正文块 | 有正文 | 1 title + 1 content_segment |
| 长正文块 | ~6300 字 | 1 title + 4 content_segment |
| 空正文标题块 | = 标题链 | 1 title |
