package io.mediagrid.storage.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.mediagrid.storage.config.StorageProperties;
import org.springframework.stereotype.Component;

/**
 * Хранилище в каталоге локальной файловой системы (mediagrid.storage.root).
 * Каталог наружу не публикуется: файлы отдаёт только служба после проверки прав или подписи ссылки.
 */
@Component
public class LocalFileStorage implements StorageProvider {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;

    public LocalFileStorage(StorageProperties properties) throws IOException {
        this.root = properties.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public long size(String key) throws IOException {
        Path path = resolve(key);
        return Files.exists(path) ? Files.size(path) : 0;
    }

    @Override
    public long append(String key, InputStream data, long maxBytes) throws IOException {
        Path path = resolve(key);
        Files.createDirectories(path.getParent());
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            int read;
            while (written < maxBytes
                    && (read = data.read(buffer, 0, (int) Math.min(buffer.length, maxBytes - written))) != -1) {
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                while (chunk.hasRemaining()) {
                    channel.write(chunk);
                }
                written += read;
            }
            // Записанное — на диск до ответа клиенту: при сбое докачка продолжится с верного места
            channel.force(false);
        }
        if (written == maxBytes && data.read() != -1) {
            throw new TooMuchDataException();
        }
        return written;
    }

    @Override
    public void truncate(String key, long size) throws IOException {
        Path path = resolve(key);
        if (Files.exists(path)) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(size);
                channel.force(false);
            }
        }
    }

    @Override
    public InputStream openRange(String key, long offset, long length) throws IOException {
        InputStream in = Files.newInputStream(resolve(key));
        try {
            in.skipNBytes(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new LimitedInputStream(in, length);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** Ключ — относительный путь внутри корня; выход за пределы корня невозможен. */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Недопустимый ключ хранилища: " + key);
        }
        return path;
    }

    /** Поток, отдающий не больше заданного числа байт. */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b != -1) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(b, off, (int) Math.min(len, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
