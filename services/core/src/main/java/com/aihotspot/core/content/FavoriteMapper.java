package com.aihotspot.core.content;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FavoriteMapper {
    int insert(@Param("userId") UUID userId, @Param("targetType") String targetType, @Param("targetId") UUID targetId);
    int delete(@Param("userId") UUID userId, @Param("targetType") String targetType, @Param("targetId") UUID targetId);
    boolean exists(@Param("userId") UUID userId, @Param("targetType") String targetType, @Param("targetId") UUID targetId);
    List<PublicContentMapper.PublicContentView> listContent(@Param("userId") UUID userId);
}
