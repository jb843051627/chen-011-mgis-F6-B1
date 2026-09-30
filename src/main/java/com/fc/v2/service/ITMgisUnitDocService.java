package com.fc.v2.service;

import com.fc.v2.model.auto.TMgisUnitDoc;

/**
 * 资质单位基础档案 Service接口
 *
 * @author fuce
 * @date 2026-09-30
 */
public interface ITMgisUnitDocService {

    /** 按主键回查档案 */
    TMgisUnitDoc selectTMgisUnitDocById(Long id);

    /**
     * 挪动档案：更新档案本身；资质单位编号（site_no）一旦变更，
     * 所有挂靠在该档案下的建档卡（t_mgis_scope_card.site_no）须同事务一并改号，
     * 任一步失败整体回滚。
     */
    int updateTMgisUnitDoc(TMgisUnitDoc record);
}
