package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TMgisScopeCardMapper;
import com.fc.v2.mapper.auto.TMgisUnitDocMapper;
import com.fc.v2.model.auto.TMgisScopeCard;
import com.fc.v2.model.auto.TMgisUnitDoc;
import com.fc.v2.service.ITMgisScopeCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 作业范围标签建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TMgisScopeCardServiceImpl extends ServiceImpl<TMgisScopeCardMapper, TMgisScopeCard> implements ITMgisScopeCardService {

    /** 档案情形：1已注销（注销档案名下不得再建新卡） */
    private static final int UNIT_STATUS_CANCELED = 1;

    @Autowired
    private TMgisUnitDocMapper mgisUnitDocMapper;

    /**
     * 详情口径：只认在库未删的卡。
     */
    @Override
    public TMgisScopeCard selectTMgisScopeCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TMgisScopeCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    /**
     * 列表口径与详情一炉：只露在库未删的卡，尊重调用方带来的筛选条件
     * （单号 / 所属档案 / 进展等）；分页由 Controller 的 startPage 统一下发，此处不另开一页。
     */
    @Override
    public List<TMgisScopeCard> selectTMgisScopeCardList(Wrapper<TMgisScopeCard> queryWrapper) {
        QueryWrapper<TMgisScopeCard> wrapper = (queryWrapper instanceof QueryWrapper)
                ? (QueryWrapper<TMgisScopeCard>) queryWrapper
                : new QueryWrapper<TMgisScopeCard>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTMgisScopeCard(TMgisScopeCard record) {
        if (record == null || record.getSiteId() == null) {
            return 0;
        }
        TMgisUnitDoc arch = loadActiveUnit(record.getSiteId());
        if (arch == null) {
            return 0;
        }
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TMgisScopeCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setSiteNo(arch.getSiteNo());
        record.setGradeLevel(Integer.valueOf(gradeOf(arch, record.getQty())));
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTMgisScopeCard(TMgisScopeCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TMgisScopeCard stored = this.baseMapper.selectOne(new QueryWrapper<TMgisScopeCard>()
                .eq("id", record.getId()).eq("del_flag", 0));
        if (stored == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TMgisScopeCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 挂靠档案：传了就校验新档案并随档案同步编号；没传沿用原档案。
        // 档案挪一挪（编号变更/改挂）只影响这一处重算，不叫历史卡的其余字段家家地震。
        Integer siteId = record.getSiteId() == null ? stored.getSiteId() : record.getSiteId();
        TMgisUnitDoc arch = loadActiveUnit(siteId);
        if (arch == null) {
            return 0;
        }
        record.setSiteId(siteId);
        record.setSiteNo(arch.getSiteNo());

        // 折算口径与新增完全一致：未传数量沿用结存数量，档位一律按同一把尺子重算，
        // 不许新增、修改两处算出两副面孔的层级档。
        BigDecimal qty = record.getQty() == null ? stored.getQty() : record.getQty();
        record.setGradeLevel(Integer.valueOf(gradeOf(arch, qty)));

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TMgisScopeCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    /** 批量软删：只落删除标记，卡与历史台账仍在库里留影 */
    @Override
    public int deleteTMgisScopeCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        return this.baseMapper.update(null, new UpdateWrapper<TMgisScopeCard>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0)
                .set("del_flag", 1));
    }

    @Override
    public int deleteTMgisScopeCardById(Long id) {
        return this.baseMapper.update(null, new UpdateWrapper<TMgisScopeCard>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("del_flag", 1));
    }

    /** 取在册（未删、未注销）的挂靠档案；查不到返回 null，调用方拒办 */
    private TMgisUnitDoc loadActiveUnit(Integer siteId) {
        TMgisUnitDoc arch = this.mgisUnitDocMapper.selectOne(new QueryWrapper<TMgisUnitDoc>()
                .eq("id", siteId).eq("del_flag", 0));
        if (arch == null || (arch.getStatus() != null && arch.getStatus() == UNIT_STATUS_CANCELED)) {
            return null;
        }
        return arch;
    }

    /**
     * 挂用类别数折算层级档（与种子口径一致：取值达到上限按高一档，故用小于号）：
     * 数量 &lt; 一档上限 → 1；&lt; 二档上限 → 2；&lt; 三档上限 → 3；其余 → 4。
     * 档案未维护的阈值档不参与判档。
     */
    private static int gradeOf(TMgisUnitDoc arch, BigDecimal qty) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) < 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) < 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
