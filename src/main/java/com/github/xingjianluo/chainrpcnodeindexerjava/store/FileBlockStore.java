package com.github.xingjianluo.chainrpcnodeindexerjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.chainrpcnodeindexerjava.chain.Block;
import com.github.xingjianluo.chainrpcnodeindexerjava.index.ChainSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单文件 JSON 区块存储。
 *
 * <p>提交语义：先写同目录临时文件并 fsync，再原子 rename 覆盖正式文件。
 * 因此即使写一半进程崩溃，正式文件要么是上一份完整快照，要么是新快照，
 * 不会出现半个文件。索引器重复写同一份快照也是幂等的（内容相同）。
 */
public class FileBlockStore implements BlockStore {

    private static final Logger log = LoggerFactory.getLogger(FileBlockStore.class);
    private static final TypeReference<List<Block>> BLOCK_LIST = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper objectMapper;

    public FileBlockStore(Path file, ObjectMapper objectMapper) {
        this.file = file;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized ChainSnapshot load() {
        if (!Files.exists(file)) {
            log.info("存储文件 {} 不存在，从空链启动", file);
            return ChainSnapshot.empty();
        }
        try {
            List<Block> blocks = objectMapper.readValue(file.toFile(), BLOCK_LIST);
            ChainSnapshot snapshot = ChainSnapshot.of(blocks);
            log.info("从 {} 恢复 {} 个区块，链头高度 {}", file, snapshot.size(), snapshot.headHeight());
            return snapshot;
        } catch (IOException e) {
            throw new UncheckedIOException("读取存储文件失败: " + file, e);
        }
    }

    @Override
    public synchronized void commit(ChainSnapshot snapshot) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp."
                    + Thread.currentThread().getId());
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(snapshot.blocks());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("提交快照失败: " + file, e);
        }
    }
}
