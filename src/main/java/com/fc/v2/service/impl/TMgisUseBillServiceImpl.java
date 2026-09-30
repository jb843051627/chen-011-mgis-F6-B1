package com.fc.v2.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TMgisUseBillMapper;
import com.fc.v2.model.auto.TMgisUseBill;
import com.fc.v2.service.ITMgisUseBillService;

/**
 * 成果提供使用逐级核准单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMgisUseBillServiceImpl implements ITMgisUseBillService {

    /** 末层：当前所在层取值 0..3，在第 3 层签足即核讫落锁 */
    private static final int LAST_NODE = 3;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TMgisUseBillMapper mgisUseBillMapper;

    @Override
    public TMgisUseBill selectTMgisUseBillById(Long id) {
        return this.mgisUseBillMapper.selectById(id);
    }

    /**
     * 签批一票。两种同层核准方式都得齐：
     * 任一人模式（0）一票即过本层；两名点齐模式（1）须已签人数凑足应签人数，
     * 没凑足就空守本层（只计签次，不进层）。过了本层：在末层则核讫落锁（status=1），
     * 否则进上一层并把已签人数清零。已核讫/已打回的单子落锁，不再受理签批。
     */
    @Override
    public TMgisUseBill approve(Long id, String approver, String comment) {
        TMgisUseBill r = this.mgisUseBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty() || isFinished(r)) {
            return null;
        }
        int signed = (r.getSignCount() == null ? 0 : r.getSignCount()) + 1;
        r.setSignCount(signed);

        if (!nodeSatisfied(r, signed)) {
            // 会签未点齐：守在本层等下一票
            r.setStatus(STATUS_RUNNING);
            r.setUpdateTime(new Date());
            this.mgisUseBillMapper.updateById(r);
            return r;
        }

        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node >= LAST_NODE) {
            r.setNodeNo(LAST_NODE);
            r.setStatus(STATUS_PASS);
        } else {
            r.setNodeNo(node + 1);
            r.setSignCount(0);
            r.setStatus(STATUS_RUNNING);
        }
        r.setUpdateTime(new Date());
        this.mgisUseBillMapper.updateById(r);
        return r;
    }

    /** 否决：落"已打回"（status=2）；已核讫/已打回的单子不再受理 */
    @Override
    public TMgisUseBill reject(Long id, String approver, String comment) {
        TMgisUseBill r = this.mgisUseBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty() || isFinished(r)) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        r.setUpdateTime(new Date());
        this.mgisUseBillMapper.updateById(r);
        return r;
    }

    /** 退回上一环节：已签人数清零；已在首层则守住首层；已核讫/已打回不得退 */
    @Override
    public TMgisUseBill rollback(Long id, String comment) {
        TMgisUseBill r = this.mgisUseBillMapper.selectById(id);
        if (r == null || isFinished(r)) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        r.setNodeNo(Math.max(0, node - 1));
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        r.setUpdateTime(new Date());
        this.mgisUseBillMapper.updateById(r);
        return r;
    }

    /** 本层签批是否凑足：任一人模式一票即可；点齐模式按应签人数，缺省按 1 人 */
    private static boolean nodeSatisfied(TMgisUseBill r, int signed) {
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode();
        if (mode != MODE_AND) {
            return true;
        }
        int need = (r.getNeedCount() == null || r.getNeedCount().intValue() <= 0)
                ? 1 : r.getNeedCount().intValue();
        return signed >= need;
    }

    /** 终态唯一判法：已核讫（1）、已打回（2）均落锁，签批/否决/退回三处共用 */
    private static boolean isFinished(TMgisUseBill r) {
        Integer st = r.getStatus();
        return st != null && (st == STATUS_PASS || st == STATUS_VETO);
    }
}
