package io.mediagrid.media.tag;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.mediagrid.support.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {

    public static final int MAX_TAGS = 20;
    public static final int MAX_LENGTH = 50;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N} _.-]+");

    private final TagRepository tags;

    public TagService(TagRepository tags) {
        this.tags = tags;
    }

    /** Приводит имена тегов к единому виду: без лишних пробелов, в нижнем регистре, без повторов. */
    public static Set<String> normalize(Collection<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String tag = name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > MAX_LENGTH || !ALLOWED.matcher(tag).matches()) {
                throw ApiException.badRequest("INVALID_TAG",
                        "Тег «" + name + "» длиннее " + MAX_LENGTH + " символов или содержит недопустимые знаки");
            }
            result.add(tag);
        }
        if (result.size() > MAX_TAGS) {
            throw ApiException.badRequest("TOO_MANY_TAGS", "Не больше " + MAX_TAGS + " тегов на файл");
        }
        return result;
    }

    /** Возвращает теги с указанными именами, создавая недостающие. */
    @Transactional
    public Set<Tag> resolve(Collection<String> names) {
        Set<String> normalized = normalize(names);
        if (normalized.isEmpty()) {
            return new LinkedHashSet<>();
        }
        normalized.forEach(name -> tags.insertIfAbsent(UUID.randomUUID(), name));
        return new LinkedHashSet<>(tags.findByNameIn(normalized));
    }
}
