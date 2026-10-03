package io.mediagrid.common.events;

import java.util.UUID;

/** Событие: запись о файле удалена из каталога, файл и его превью нужно удалить из хранилища. */
public record MediaDeletedEvent(UUID mediaId) {
}
