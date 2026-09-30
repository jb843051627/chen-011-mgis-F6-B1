package com.fc.v2.service.impl;

import java.util.Date;
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

    /** 关口上限：当前关口取值 0..5 */
    private static final int MAX_STAGE = 5;
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

    /**
     * 推进一档（不是两档——关口须逐口过）；推到顶关口才销号（status=1），
     * 半途各口仍是在办（status=0）。已销号/已封存落了锁，不得再推。
     */
    @Override
    public TMgisCheckCard advance(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        int next = Math.min(st + 1, MAX_STAGE);
        r.setStage(next);
        r.setStatus(next >= MAX_STAGE ? STATUS_CLOSED : STATUS_RUNNING);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.mgisCheckCardMapper.updateById(r);
        return r;
    }

    /**
     * 回退一档：从当前关口退一口，已在第 0 口则守住 0，不一退到底。
     * 已销号/已封存同样落锁，回退也被拒。
     */
    @Override
    public TMgisCheckCard rollback(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        r.setStage(Math.max(0, st - 1));
        r.setStatus(STATUS_RUNNING);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.mgisCheckCardMapper.updateById(r);
        return r;
    }

    /** 已销号/已封存的回执不得再改记事 */
    @Override
    public boolean updateContent(Long id, String remark) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        r.setContent(remark);
        r.setUpdateTime(new Date());
        return this.mgisCheckCardMapper.updateById(r) > 0;
    }

    /** 已销号/已封存的回执不得删除 */
    @Override
    public boolean remove(Long id) {
        TMgisCheckCard r = this.mgisCheckCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        return this.mgisCheckCardMapper.deleteById(id) > 0;
    }

    /** 终态唯一判法：已销号（1）、已封存（2）均落锁，推进/回退/改记事/删除四处共用 */
    private static boolean isTerminal(TMgisCheckCard r) {
        Integer st = r.getStatus();
        return st != null && (st == STATUS_CLOSED || st == STATUS_SEALED);
    }

}
