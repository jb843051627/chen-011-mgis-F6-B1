package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TMgisSubmitRowMapper;
import com.fc.v2.model.auto.TMgisSubmitRow;
import com.fc.v2.service.ITMgisSubmitRowService;

/**
 * 成果目录汇交核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TMgisSubmitRowServiceImpl implements ITMgisSubmitRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TMgisSubmitRowMapper mgisSubmitRowMapper;

    @Override
    public TMgisSubmitRow selectTMgisSubmitRowById(Long id) {
        return this.mgisSubmitRowMapper.selectById(id);
    }

    /**
     * 整批提交：逐行校验，合法的入库、非法的记入失败明细（保留原册行号），返回**本次成功行数**。
     * 单行不合法只挡它自己——绕过去接着办，不掀整桌；同册重复提交不重复入库；
     * 行数超 {@link #MAX_ROWS} 返回 -1 且整批不入库；空册 / 无册号返回 0。
     */
    @Override
    public int submitBatch(String batchNo, List<TMgisSubmitRow> rows) {
        if (batchNo == null || batchNo.trim().isEmpty() || rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }

        Integer existed = this.mgisSubmitRowMapper.selectCount(new QueryWrapper<TMgisSubmitRow>()
                .eq("batch_no", batchNo).eq("del_flag", 0));
        if (existed != null && existed > 0) {
            // 同册已交过：幂等返回已入账行数，不重复落行
            Integer booked = this.mgisSubmitRowMapper.selectCount(new QueryWrapper<TMgisSubmitRow>()
                    .eq("batch_no", batchNo).eq("status", STATUS_OK).eq("del_flag", 0));
            return booked == null ? 0 : booked;
        }

        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            TMgisSubmitRow r = rows.get(i);
            if (r == null) {
                continue;
            }
            r.setBatchNo(batchNo);
            if (r.getRowNo() == null) {
                r.setRowNo(Integer.valueOf(i + 1));
            }
            if (!isValid(r)) {
                r.setStatus(STATUS_FAIL);
                safeInsert(r);
                continue;
            }
            r.setStatus(STATUS_OK);
            if (safeInsert(r) > 0) {
                ok++;
            }
        }
        return ok;
    }

    /** 图幅代号非空、幅数为正方可入账 */
    private static boolean isValid(TMgisSubmitRow r) {
        return r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                && r.getQty() != null
                && r.getQty().compareTo(BigDecimal.ZERO) > 0;
    }

    /** 单条落库抛错只丢这一条，不连累整批 */
    private int safeInsert(TMgisSubmitRow r) {
        try {
            return this.mgisSubmitRowMapper.insert(r);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @Override
    public List<TMgisSubmitRow> listErrors(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return new java.util.ArrayList<TMgisSubmitRow>();
        }
        return this.mgisSubmitRowMapper.selectList(new QueryWrapper<TMgisSubmitRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0)
                .orderByAsc("row_no"));
    }
}
