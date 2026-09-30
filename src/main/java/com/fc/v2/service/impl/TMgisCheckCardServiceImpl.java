package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMgisCheckCardMapper;
import com.fc.v2.model.auto.TMgisCheckCard;
import com.fc.v2.service.ITMgisCheckCardService;

/**
 * 不合格成果整改回执单 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMgisCheckCardServiceImpl implements ITMgisCheckCardService {

    private static final int MAX_STAGE = 3;
    /** 回执落定 0在办 1已销号 2已封存 */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_CLOSED = 1;
    private static final int STATUS_SEALED = 2;

    @javax.annotation.Resource
    private TMgisCheckCardMapper mgisCheckCardMapper;

    @Override
    public TMgisCheckCard selectTMgisCheckCardById(Long id) {
        return this.mgisCheckCardMapper.selectById(id);
    }

    @Override
    public List<TMgisCheckCard> selectTMgisCheckCardList(QueryWrapper<TMgisCheckCard> queryWrapper) {
        return this.mgisCheckCardMapper.selectList(queryWrapper);
    }

    @Override
    public TMgisCheckCard advance(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isFinished(r)) {
            // 已销号/已封存不得继续推进
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        int next = Math.min(st + 1, MAX_STAGE);
        r.setStage(next);
        // 走到末道关口才落锁销号，途中一律仍在办
        r.setStatus(next >= MAX_STAGE ? STATUS_CLOSED : STATUS_RUNNING);
        r.setLastAction(remark);
        this.mgisCheckCardMapper.updateById(r);
        return r;
    }

    @Override
    public TMgisCheckCard rollback(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isFinished(r)) {
            // 已销号/已封存不得回退
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        r.setStage(Math.max(0, st - 1));
        r.setStatus(STATUS_RUNNING);
        r.setLastAction(remark);
        this.mgisCheckCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isFinished(r)) {
            // 已办结不得再改
            return false;
        }
        r.setContent(remark);
        return this.mgisCheckCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isFinished(r)) {
            // 已办结不得删除
            return false;
        }
        return this.mgisCheckCardMapper.deleteById(id) > 0;
    }

    /** 终态判法全类一口径：非"在办"即已办结 */
    private static boolean isFinished(TMgisCheckCard r) {
        return r.getStatus() != null
                && (r.getStatus() == STATUS_CLOSED || r.getStatus() == STATUS_SEALED);
    }

}
