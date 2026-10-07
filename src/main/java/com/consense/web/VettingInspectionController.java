package com.consense.web;

import com.consense.common.ApiResponse;
import com.consense.service.vetting.VettingInspectionService;
import com.consense.service.vetting.VettingInspectionOcrReview;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

@RestController
@RequestMapping("/api/vetting-inspection")
public class VettingInspectionController {
    private final VettingInspectionService inspection;
    private final VettingInspectionOcrReview ocrReview;
    public VettingInspectionController(VettingInspectionService inspection){this(inspection,new VettingInspectionOcrReview());}
    @Autowired public VettingInspectionController(VettingInspectionService inspection,VettingInspectionOcrReview ocrReview){this.inspection=inspection;this.ocrReview=ocrReview;}
    @GetMapping("/catalog") public ApiResponse<?> catalog(){return ApiResponse.ok(inspection.catalog());}
    @GetMapping("/chunks") public ApiResponse<?> chunks(@RequestParam("datasetId") String datasetId,@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="30") int pageSize,@RequestParam(name="q",required=false) String q,@RequestParam(name="documentId",required=false) String documentId,@RequestParam(name="role",required=false) String role)throws IOException{return ApiResponse.ok(inspection.chunks(datasetId,page,pageSize,q,documentId,role));}
    @GetMapping("/chunks/{id}") public ApiResponse<?> chunk(@PathVariable("id") String id,@RequestParam("datasetId") String datasetId)throws IOException{return ApiResponse.ok(inspection.chunk(datasetId,id));}
    @GetMapping("/documents") public ApiResponse<?> documents(@RequestParam("datasetId") String datasetId,@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="30") int pageSize)throws IOException{return ApiResponse.ok(inspection.documents(datasetId,page,pageSize));}
    @GetMapping("/documents/{id}/pages") public ApiResponse<?> pages(@PathVariable("id") String id,@RequestParam("datasetId") String datasetId,@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="30") int pageSize)throws IOException{return ApiResponse.ok(inspection.pages(datasetId,id,page,pageSize));}
    @GetMapping("/documents/{id}/pages/{pageNo}") public ApiResponse<?> page(@PathVariable("id") String id,@PathVariable("pageNo") int pageNo,@RequestParam("datasetId") String datasetId)throws IOException{Map<String,Object> page=new java.util.LinkedHashMap<>(inspection.page(datasetId,id,pageNo));page.put("ocrReview",ocrReview.attach(datasetId,id,pageNo,page));return ApiResponse.ok(page);}
    @GetMapping("/documents/{id}/pages/{pageNo}/ocr-assets/{assetId}") public ResponseEntity<ByteArrayResource> ocrAsset(@PathVariable("id") String id,@PathVariable("pageNo") int pageNo,@PathVariable("assetId") String assetId,@RequestParam("datasetId") String datasetId)throws IOException{try{byte[] image=ocrReview.image(datasetId,id,pageNo,assetId,inspection.page(datasetId,id,pageNo));return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").body(new ByteArrayResource(image));}catch(IOException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).header("X-Content-Type-Options","nosniff").build();}}
    @GetMapping("/documents/{id}/original") public ResponseEntity<FileSystemResource> original(@PathVariable("id") String id,@RequestParam("datasetId") String datasetId)throws IOException{Path p=inspection.original(datasetId,id);String mime=Files.probeContentType(p);MediaType type=mime!=null?MediaType.parseMediaType(mime):MediaType.APPLICATION_OCTET_STREAM;return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(p.getFileName().toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(p));}
    @GetMapping("/runs") public ApiResponse<?> runs(){return ApiResponse.ok(inspection.runs());}
    @GetMapping("/runs/{id}/tasks") public ApiResponse<?> tasks(@PathVariable("id") String id,@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="30") int pageSize)throws IOException{return ApiResponse.ok(inspection.tasks(id,page,pageSize));}
    @GetMapping("/runs/{id}/tasks/{taskId}") public ApiResponse<?> task(@PathVariable("id") String id,@PathVariable("taskId") String taskId,@RequestParam(name="stage",defaultValue="rerank") String stage,@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="30") int pageSize)throws IOException{return ApiResponse.ok(inspection.task(id,taskId,stage,page,pageSize));}
}
