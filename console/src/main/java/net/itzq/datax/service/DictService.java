package net.itzq.datax.service;

import net.itzq.datax.common.BizException;
import net.itzq.datax.common.IdGen;
import net.itzq.datax.connector.TypeRuleProvider;
import net.itzq.datax.entity.DictType;
import net.itzq.datax.entity.DictValue;
import net.itzq.datax.entity.TypeMap;
import net.itzq.datax.mapper.SysDictMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 字典与类型映射的管理服务，同时作为 connector 层 {@link TypeRuleProvider} 的实现
 * （decide 查表入口）。
 */
@Service
public class DictService implements TypeRuleProvider {

    private final SysDictMapper mapper;

    public DictService(SysDictMapper mapper) {
        this.mapper = mapper;
    }

    // ── 类型映射规则查询（decide 热路径） ──

    @Override
    public String resolve(String sourceBrand, String targetBrand, String sourceTypeBase) {
        if (sourceBrand == null || targetBrand == null || sourceTypeBase == null) {
            return null;
        }
        TypeMap m = mapper.findTypeMap(sourceBrand + "->" + targetBrand, sourceTypeBase.toLowerCase());
        return m == null ? null : m.getTargetValue();
    }

    // ── 字典类型 ──

    public List<DictType> dictTypes() {
        return mapper.findDictTypes();
    }

    public DictType createDictType(DictType t) {
        require(t.getCode(), "字典编码不能为空");
        require(t.getName(), "字典名称不能为空");
        if (mapper.findDictTypeByCode(t.getCode()) != null) {
            throw new BizException("字典编码已存在: " + t.getCode());
        }
        t.setId(IdGen.uuid());
        t.setCreateDate(new Date());
        t.setDelFlag("0");
        mapper.insertDictType(t);
        return t;
    }

    public DictType updateDictType(String id, DictType t) {
        DictType old = requireDictType(id);
        old.setName(t.getName());
        old.setRemark(t.getRemark());
        old.setUpdateDate(new Date());
        mapper.updateDictType(old);
        return old;
    }

    public void deleteDictType(String id) {
        DictType old = requireDictType(id);
        if (old.getIsSystem() != null && old.getIsSystem() == 1) {
            throw new BizException("系统预设字典不可删除（可删除其下的自定义条目）");
        }
        mapper.deleteDictType(id, System.currentTimeMillis());
    }

    // ── 字典值 ──

    public List<DictValue> dictValues(String dictCode) {
        return mapper.findDictValues(dictCode);
    }

    public DictValue createDictValue(DictValue v) {
        require(v.getDictCode(), "所属字典不能为空");
        require(v.getLabel(), "类型名不能为空");
        // value = 默认长度，允许为空（如 varchar -> 255、int -> 空）
        v.setValue(v.getValue() == null ? "" : v.getValue().trim());
        v.setId(IdGen.uuid());
        v.setCreateDate(new Date());
        v.setDelFlag("0");
        mapper.insertDictValue(v);
        return v;
    }

    public DictValue updateDictValue(String id, DictValue v) {
        DictValue old = mapper.findDictValueById(id);
        if (old == null) {
            throw new BizException("字典条目不存在");
        }
        old.setLabel(v.getLabel());
        old.setValue(v.getValue() == null ? "" : v.getValue().trim());
        old.setSort(v.getSort());
        old.setRemark(v.getRemark());
        old.setUpdateDate(new Date());
        mapper.updateDictValue(old);
        return old;
    }

    public void deleteDictValue(String id) {
        mapper.deleteDictValue(id, System.currentTimeMillis());
    }

    // ── 类型映射 ──

    public List<TypeMap> typeMaps(String group) {
        return mapper.findTypeMaps(group);
    }

    public List<String> typeMapGroups() {
        return mapper.findTypeMapGroups();
    }

    public TypeMap createTypeMap(TypeMap m) {
        require(m.getGroupName(), "映射方向分组不能为空");
        require(m.getSourceType(), "源类型不能为空");
        require(m.getTargetValue(), "目标 DDL 类型不能为空");
        String group = normalizeGroup(m.getGroupName());
        String base = m.getSourceType().trim().toLowerCase();
        if (mapper.findTypeMap(group, base) != null) {
            throw new BizException("该方向的源类型已有映射规则: " + base
                    + "（请直接修改既有规则）");
        }
        m.setId(IdGen.uuid());
        m.setGroupName(group);
        m.setSourceType(base);
        m.setCreateDate(new Date());
        m.setDelFlag("0");
        mapper.insertTypeMap(m);
        return m;
    }

    public TypeMap updateTypeMap(String id, TypeMap m) {
        TypeMap old = mapper.findTypeMapById(id);
        if (old == null) {
            throw new BizException("映射规则不存在");
        }
        require(m.getTargetValue(), "目标 DDL 类型不能为空");
        old.setTargetValue(m.getTargetValue());
        old.setTargetLabel(m.getTargetLabel());
        old.setRemark(m.getRemark());
        old.setUpdateDate(new Date());
        mapper.updateTypeMap(old);
        return old;
    }

    public void deleteTypeMap(String id) {
        mapper.deleteTypeMap(id, System.currentTimeMillis());
    }

    private static String normalizeGroup(String group) {
        return group.trim().toLowerCase();
    }

    private DictType requireDictType(String id) {
        List<DictType> all = mapper.findDictTypes();
        for (DictType t : all) {
            if (t.getId().equals(id)) {
                return t;
            }
        }
        throw new BizException("字典不存在");
    }

    private static void require(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BizException(message);
        }
    }
}
