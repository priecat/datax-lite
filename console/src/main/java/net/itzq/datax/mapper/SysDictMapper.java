package net.itzq.datax.mapper;

import net.itzq.datax.entity.DictType;
import net.itzq.datax.entity.DictValue;
import net.itzq.datax.entity.TypeMap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 字典（sys_dict_type / sys_dict_value）与类型映射（sys_type_map）的持久化 */
@Mapper
public interface SysDictMapper {

    // ── 字典类型 ──
    List<DictType> findDictTypes();

    DictType findDictTypeByCode(@Param("code") String code);

    void insertDictType(DictType t);

    void updateDictType(DictType t);

    void deleteDictType(@Param("id") String id, @Param("now") long now);

    // ── 字典值 ──
    List<DictValue> findDictValues(@Param("dictCode") String dictCode);

    DictValue findDictValueById(@Param("id") String id);

    void insertDictValue(DictValue v);

    void updateDictValue(DictValue v);

    void deleteDictValue(@Param("id") String id, @Param("now") long now);

    // ── 类型映射 ──
    List<TypeMap> findTypeMaps(@Param("group") String group);

    List<String> findTypeMapGroups();

    TypeMap findTypeMapById(@Param("id") String id);

    /** decide 查表入口：group + source_type 唯一定位（未命中返回 null） */
    TypeMap findTypeMap(@Param("group") String group, @Param("sourceType") String sourceType);

    void insertTypeMap(TypeMap m);

    void updateTypeMap(TypeMap m);

    void deleteTypeMap(@Param("id") String id, @Param("now") long now);
}
