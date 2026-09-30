package com.fc.v2.service.impl;

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
    /** 行进展 0待销 1已入账 2挂驳回 */
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TMgisSubmitRowMapper mgisSubmitRowMapper;

    @Override
    public TMgisSubmitRow selectTMgisSubmitRowById(Long id) {
        return this.mgisSubmitRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TMgisSubmitRow> rows) {
        // 无件可办的轮次报零，不得取首行而摔错
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 行数超过单批上限：-1 且整批不入库
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        // 同一批次重复提交不得重复入库
        Integer exist = this.mgisSubmitRowMapper.selectCount(new QueryWrapper<TMgisSubmitRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", 0));
        if (exist != null && exist > 0) {
            return 0;
        }
        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            TMgisSubmitRow r = rows.get(i);
            // 保留原册内行次；缺失时按册内顺序从 1 起补
            if (r.getRowNo() == null) {
                r.setRowNo(Integer.valueOf(i + 1));
            }
            r.setBatchNo(batchNo);
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                r.setStatus(STATUS_FAIL);
            } else {
                r.setStatus(STATUS_OK);
                ok++;
            }
            this.mgisSubmitRowMapper.insert(r);
        }
        return ok;
    }

    @Override
    public List<TMgisSubmitRow> listErrors(String batchNo) {
        return this.mgisSubmitRowMapper.selectList(new QueryWrapper<TMgisSubmitRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
    }
}
