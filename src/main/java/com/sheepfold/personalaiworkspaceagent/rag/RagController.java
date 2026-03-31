package com.sheepfold.personalaiworkspaceagent.rag;

import com.sheepfold.personalaiworkspaceagent.rag.dto.RagAskRequest;
import com.sheepfold.personalaiworkspaceagent.rag.dto.RagIngestRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG", description = "RAG 入库、问答与 Chroma 调试接口")
public class RagController {

    private final RagService ragService;
    private final ChromaDebugService chromaDebugService;

    public RagController(RagService ragService, ChromaDebugService chromaDebugService) {
        this.ragService = ragService;
        this.chromaDebugService = chromaDebugService;
    }

    @GetMapping("/collections")
    @Operation(summary = "列出 Chroma 集合", description = "列出当前租户和数据库下的集合")
    public Map<String, Object> collections() {
        return chromaDebugService.listCollections();
    }

    @GetMapping("/vectors")
    @Operation(summary = "浏览已存向量", description = "分页查询当前 Chroma 集合中的向量与文档")
    public Map<String, Object> vectors(
            @Parameter(description = "每页条数，最大 200") @RequestParam(defaultValue = "20") Integer limit,
            @Parameter(description = "起始偏移量") @RequestParam(defaultValue = "0") Integer offset,
            @Parameter(description = "为 true 时返回向量预览（embedding）") @RequestParam(defaultValue = "false") boolean withEmbedding) {
        return chromaDebugService.listVectors(limit, offset, withEmbedding);
    }

    @DeleteMapping("/vectors/by-source")
    @Operation(summary = "按来源文件删除向量", description = "删除 metadata.source_file_name 等于 sourceFileName 的向量")
    public Map<String, Object> deleteVectorsBySource(
            @Parameter(description = "source_file_name 元数据值") @RequestParam String sourceFileName) {
        return chromaDebugService.deleteVectorsBySourceFileName(sourceFileName);
    }

    @PostMapping("/ingest")
    @Operation(summary = "写入文本分片", description = "将输入的文本分片写入向量库")
    public Map<String, Object> ingest(@RequestBody RagIngestRequest request) {
        int count = ragService.ingest(request);
        return Map.of("ingested", count);
    }

    @PostMapping(value = "/ingest/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档入库", description = "使用 LlamaParse 解析文件并自动切片后写入向量库")
    public Map<String, Object> ingestFile(@RequestPart("file") MultipartFile file) {
        RagService.IngestFileResult result = ragService.ingestFile(file);
        return Map.of(
                "fileName", result.fileName(),
                "ingested", result.ingested(),
                "chunkCount", result.chunkCount(),
                "parser", "llamaparse");
    }

    @PostMapping("/ask")
    @Operation(summary = "RAG 问答", description = "基于 Advisor 检索增强并生成回答，同时返回命中文件名用于溯源")
    public Map<String, Object> ask(@RequestBody RagAskRequest request) {
        RagService.RagAnswer result = ragService.ask(request.question(), request.topK());
        return Map.of(
                "answer", result.answer(),
                "hitCount", result.hitCount(),
                "sourceFiles", result.sourceFiles());
    }
}
