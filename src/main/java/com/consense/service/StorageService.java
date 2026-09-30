package com.consense.service;

import com.consense.common.BizException;
import com.consense.config.ConsenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 上传文件落盘：data/uploads/{projectId}/{category}/{uuid}_{原文件名}
 */
@Slf4j
@Service
public class StorageService {

    private final Path root;

    public StorageService(ConsenseProperties props) {
        this.root = Paths.get(props.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("文件存储目录: {}", root);
        } catch (IOException e) {
            throw new BizException("无法创建存储目录 " + root + ": " + e.getMessage());
        }
    }

    public StoredFile store(String projectId, String category, MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String safeName = original.replaceAll("[\\\\/:*?\"<>|]", "_");
        String key = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
        Path dir = root.resolve(projectId).resolve(category);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(key);
            InputStream in = file.getInputStream();
            try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
            }
            return new StoredFile(original, target.toString(), file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException("保存文件失败: " + e.getMessage());
        }
    }

    public byte[] read(String storagePath) {
        try {
            return Files.readAllBytes(Paths.get(storagePath));
        } catch (IOException e) {
            throw new BizException("读取文件失败: " + e.getMessage());
        }
    }

    /** 落盘结果（构造器签名与原 record 一致） */
    public static final class StoredFile {

        private final String originalName;
        private final String path;
        private final long size;
        private final String contentType;

        public StoredFile(String originalName, String path, long size, String contentType) {
            this.originalName = originalName;
            this.path = path;
            this.size = size;
            this.contentType = contentType;
        }

        public String getOriginalName() {
            return originalName;
        }

        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
