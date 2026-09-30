package com.fc.v2.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TMgisScopeCardMapper;
import com.fc.v2.mapper.auto.TMgisUnitDocMapper;
import com.fc.v2.model.auto.TMgisScopeCard;
import com.fc.v2.model.auto.TMgisUnitDoc;
import com.fc.v2.service.ITMgisUnitDocService;

/**
 * 资质单位基础档案 Service业务层处理
 *
 * @author fuce
 * @date 2026-09-30
 */
@Service
public class TMgisUnitDocServiceImpl implements ITMgisUnitDocService {

    @javax.annotation.Resource
    private TMgisUnitDocMapper mgisUnitDocMapper;

    @javax.annotation.Resource
    private TMgisScopeCardMapper mgisScopeCardMapper;

    @Override
    public TMgisUnitDoc selectTMgisUnitDocById(Long id) {
        return this.mgisUnitDocMapper.selectById(id);
    }

    /**
     * 挪动档案：档案编号一改，底下挂靠的建档卡家家都要跟着改号。
     * 用一条批量更新兜住全部挂靠记录，并与档案本身的更新放在同一事务里，
     * 任一步失败整体回滚，避免档案改了、挂靠记录还留着旧编号。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTMgisUnitDoc(TMgisUnitDoc record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TMgisUnitDoc old = this.mgisUnitDocMapper.selectById(record.getId());
        if (old == null) {
            return 0;
        }
        record.setUpdateTime(new Date());
        int rows = this.mgisUnitDocMapper.updateById(record);

        String newNo = record.getSiteNo();
        if (newNo != null && !newNo.equals(old.getSiteNo())) {
            // 挂靠记录按 site_id 认主，批量把仍正常的建档卡编号刷成新号
            this.mgisScopeCardMapper.update(null, new UpdateWrapper<TMgisScopeCard>()
                    .eq("site_id", record.getId())
                    .eq("del_flag", 0)
                    .set("site_no", newNo)
                    .set("update_time", new Date()));
        }
        return rows;
    }
}
