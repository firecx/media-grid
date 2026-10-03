package io.mediagrid.storage.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Единый внутренний интерфейс доступа к хранилищу (ТЗ, п. 4.1.10). Остальная служба работает только
 * с ним, поэтому переход, например, на объектное хранилище — это новая реализация этого интерфейса.
 * Ключ — внутреннее имя объекта; наружу он не выдаётся.
 */
public interface StorageProvider {

    /** Название способа хранения; записывается рядом с ключом в stored_files.storage_backend. */
    String name();

    /** Сколько байт объекта уже записано; 0, если объекта нет. */
    long size(String key) throws IOException;

    /**
     * Дописывает в конец объекта не больше maxBytes из потока, не держа данные в памяти целиком.
     * Записанное надёжно сохраняется до возврата. Если в потоке больше maxBytes,
     * первые maxBytes записываются, а затем бросается {@link TooMuchDataException}.
     *
     * @return сколько байт записано (меньше maxBytes, если поток закончился раньше)
     */
    long append(String key, InputStream data, long maxBytes) throws IOException;

    /** Обрезает объект до size байт — откат неудачно дописанной части. */
    void truncate(String key, long size) throws IOException;

    /** Поток для чтения части объекта: length байт, начиная с offset. */
    InputStream openRange(String key, long offset, long length) throws IOException;

    /** Удаляет объект; отсутствие объекта — не ошибка. */
    void delete(String key) throws IOException;

    /**
     * Ссылка на объект, которую хранилище выдаёт само (например, подписанная ссылка объектного хранилища).
     * Если хранилище так не умеет, ссылку выдаёт служба и раздаёт файл сама.
     */
    default Optional<URI> directUrl(String key, Duration ttl) {
        return Optional.empty();
    }

    /** В потоке оказалось больше данных, чем было заявлено. */
    class TooMuchDataException extends IOException {
        public TooMuchDataException() {
            super("В потоке больше данных, чем заявлено");
        }
    }
}
