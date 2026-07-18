package com.aihotspot.core.content;

import com.aihotspot.core.api.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
    private final FavoriteMapper mapper;
    private final PublicContentMapper contentMapper;
    private final PublicDiscoveryMapper discoveryMapper;
    public FavoriteService(FavoriteMapper mapper, PublicContentMapper contentMapper, PublicDiscoveryMapper discoveryMapper) {
        this.mapper = mapper; this.contentMapper = contentMapper; this.discoveryMapper = discoveryMapper;
    }
    public List<PublicContentMapper.PublicContentView> list(UUID userId) { return mapper.listContent(userId); }
    public boolean exists(UUID userId, String requestedType, UUID targetId) { return mapper.exists(userId, type(requestedType), targetId); }
    @Transactional
    public void add(UUID userId, String requestedType, UUID targetId) {
        String type = type(requestedType);
        boolean visible = "CONTENT".equals(type) ? contentMapper.findPublicById(targetId) != null : discoveryMapper.findEvent(targetId) != null;
        if (!visible) throw new ApiException(HttpStatus.NOT_FOUND, "FAVORITE_TARGET_NOT_FOUND", "收藏对象不存在或不可访问");
        mapper.insert(userId, type, targetId);
    }
    public void remove(UUID userId, String requestedType, UUID targetId) { mapper.delete(userId, type(requestedType), targetId); }
    private String type(String value) {
        String normalized = value == null ? "CONTENT" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("CONTENT") && !normalized.equals("EVENT"))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FAVORITE_TYPE", "收藏类型只支持 CONTENT 或 EVENT");
        return normalized;
    }
}
