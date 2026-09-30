package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.fc.v2.mapper.auto.TMgisCheckCardMapper;
import com.fc.v2.mapper.auto.TMgisDutyTaskMapper;
import com.fc.v2.mapper.auto.TMgisQualLineMapper;
import com.fc.v2.mapper.auto.TMgisScopeCardMapper;
import com.fc.v2.mapper.auto.TMgisSubmitRowMapper;
import com.fc.v2.mapper.auto.TMgisUnitDocMapper;
import com.fc.v2.mapper.auto.TMgisUseBillMapper;
import com.fc.v2.model.auto.TMgisCheckCard;
import com.fc.v2.model.auto.TMgisDutyTask;
import com.fc.v2.model.auto.TMgisQualLine;
import com.fc.v2.model.auto.TMgisScopeCard;
import com.fc.v2.model.auto.TMgisSubmitRow;
import com.fc.v2.model.auto.TMgisUnitDoc;
import com.fc.v2.model.auto.TMgisUseBill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 六个 mgis 业务服务的纯逻辑单测（Mapper 全部 mock，不碰库） */
@ExtendWith(MockitoExtension.class)
public class TMgisServiceLogicTest {

    @Mock
    private TMgisDutyTaskMapper dutyMapper;
    @Mock
    private TMgisQualLineMapper qualMapper;
    @Mock
    private TMgisSubmitRowMapper submitMapper;
    @Mock
    private TMgisScopeCardMapper scopeMapper;
    @Mock
    private TMgisUnitDocMapper unitDocMapper;
    @Mock
    private TMgisCheckCardMapper checkMapper;
    @Mock
    private TMgisUseBillMapper useBillMapper;

    private static Date t(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("GMT+8"));
        return f.parse(s);
    }

    // ---------- 到期提醒派发条目 ----------

    private TMgisDutyTaskServiceImpl dutyService() {
        TMgisDutyTaskServiceImpl s = new TMgisDutyTaskServiceImpl();
        ReflectionTestUtils.setField(s, "mgisDutyTaskMapper", dutyMapper);
        return s;
    }

    private TMgisDutyTask dutyItem(String itemNo, Date dueAt, String leadDays, Integer status) {
        TMgisDutyTask r = new TMgisDutyTask();
        r.setId(1L);
        r.setItemNo(itemNo);
        r.setDueAt(dueAt);
        r.setAmount(new BigDecimal(leadDays));
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void duty_emptyRoundReturnsZeroInsteadOfThrowing() throws Exception {
        when(dutyMapper.selectList(any())).thenReturn(new ArrayList<TMgisDutyTask>());
        assertEquals(0, dutyService().runOnce(t("2026-10-01 03:00:00")));
    }

    @Test
    public void duty_outOfRunHourWindowNothingDispatched() throws Exception {
        // 06:00 已出 02..05 点的执行时辰窗：条目白白处理的事不能再有
        assertEquals(0, dutyService().runOnce(t("2026-10-01 06:00:00")));
        verify(dutyMapper, never()).selectList(any());
        verify(dutyMapper, never()).updateById(any());
    }

    @Test
    public void duty_dueAtExactRemindPointIsIncluded() throws Exception {
        // 正卡在提醒点上（due - amount）的那一条，不得漏
        TMgisDutyTask r = dutyItem("D1", t("2026-10-03 05:00:00"), "0", 0);
        when(dutyMapper.selectList(any())).thenReturn(Collections.singletonList(r));
        when(dutyMapper.updateById(any())).thenReturn(1);
        assertEquals(1, dutyService().runOnce(t("2026-10-03 05:00:00")));
        assertEquals(1, r.getStatus().intValue());
    }

    @Test
    public void duty_doneItemNotPickedUpAgain() throws Exception {
        // 库里只捞 status=0：查询条件本身要带 status / del_flag 双锁，
        // 模拟数据库按条件过滤后没有待办条目，已提醒/送不出去的下一轮空守
        when(dutyMapper.selectList(any())).thenReturn(new ArrayList<TMgisDutyTask>());
        assertEquals(0, dutyService().runOnce(t("2026-10-03 03:00:00")));

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TMgisDutyTask>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(dutyMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("del_flag"), "落锁条件须带删除标记: " + sql);
        assertTrue(sql.contains("status"), "落锁条件须带条目情形: " + sql);
        verify(dutyMapper, never()).updateById(any());
    }

    @Test
    public void duty_oneBadRowDoesNotFlipTheTable() throws Exception {
        TMgisDutyTask bad = dutyItem("  ", t("2026-10-03 03:00:00"), "0", 0);
        TMgisDutyTask good = dutyItem("D2", t("2026-10-03 03:00:00"), "0", 0);
        when(dutyMapper.selectList(any())).thenReturn(Arrays.asList(bad, good));
        when(dutyMapper.updateById(any())).thenReturn(1);
        assertEquals(1, dutyService().runOnce(t("2026-10-03 03:00:00")));
        assertEquals(2, bad.getStatus().intValue());
        assertEquals(1, good.getStatus().intValue());
    }

    // ---------- 质检判定线 ----------

    private TMgisQualLineServiceImpl qualService() {
        TMgisQualLineServiceImpl s = new TMgisQualLineServiceImpl();
        ReflectionTestUtils.setField(s, "mgisQualLineMapper", qualMapper);
        return s;
    }

    private TMgisQualLine line(Long id, String code, String th1, String th2, String th3,
                               String start, String end, Integer priority, Integer status) throws Exception {
        TMgisQualLine r = new TMgisQualLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setTh1Max(new BigDecimal(th1));
        r.setTh2Max(new BigDecimal(th2));
        r.setTh3Max(new BigDecimal(th3));
        r.setEffStart(start == null ? null : t(start));
        r.setEffEnd(end == null ? null : t(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void qual_effStartBoundaryIncludedEffEndExcluded() throws Exception {
        TMgisQualLine r = line(1L, "R", "1", "2", "3",
                "2026-01-01 00:00:00", "2026-07-01 00:00:00", 1, 0);
        when(qualMapper.selectList(any())).thenReturn(Collections.singletonList(r));
        TMgisQualLineServiceImpl s = qualService();
        assertEquals(2, s.evaluate("R", new BigDecimal("1"), t("2026-01-01 00:00:00")),
                "启用当日不可漏；等于一档上限取高一档");
        assertEquals(2, s.evaluate("R", new BigDecimal("1"), t("2026-01-02 00:00:00")));
        assertEquals(0, s.evaluate("R", new BigDecimal("1"), t("2026-07-01 00:00:00")),
                "交棒日不含，当日已不可用");
    }

    @Test
    public void qual_thresholdEqualGoesUpOneTier() throws Exception {
        TMgisQualLine r = line(1L, "R", "1", "2", "3",
                "2026-01-01 00:00:00", null, 1, 0);
        when(qualMapper.selectList(any())).thenReturn(Collections.singletonList(r));
        TMgisQualLineServiceImpl s = qualService();
        assertEquals(2, s.evaluate("R", new BigDecimal("1.00"), t("2026-02-01 00:00:00")));
        assertEquals(4, s.evaluate("R", new BigDecimal("3.00"), t("2026-02-01 00:00:00")));
    }

    @Test
    public void qual_topPicksByPriorityAndUsableSharesOneRule() throws Exception {
        TMgisQualLine low = line(1L, "A", "10", "20", "30",
                "2026-01-01 00:00:00", null, 5, 0);
        TMgisQualLine high = line(2L, "B", "1", "2", "3",
                "2026-01-01 00:00:00", null, 9, 0);
        TMgisQualLine retired = line(3L, "C", "1", "2", "3",
                "2026-01-01 00:00:00", null, 99, 1);
        when(qualMapper.selectList(any())).thenReturn(Arrays.asList(low, high, retired));
        when(qualMapper.selectById(2L)).thenReturn(high);
        when(qualMapper.selectById(3L)).thenReturn(retired);
        TMgisQualLineServiceImpl s = qualService();
        Date at = t("2026-02-01 00:00:00");
        assertEquals(1, s.evaluateTop(new BigDecimal("0.5"), at), "取优先级最高的当家规则");
        assertEquals(2, s.countAvailable(at));
        assertTrue(s.usable(2L, at));
        assertFalse(s.usable(3L, at), "已让位的规则与列表同一判法");
    }

    // ---------- 整批提交 ----------

    private TMgisSubmitRowServiceImpl submitService() {
        TMgisSubmitRowServiceImpl s = new TMgisSubmitRowServiceImpl();
        ReflectionTestUtils.setField(s, "mgisSubmitRowMapper", submitMapper);
        return s;
    }

    @Test
    public void submit_emptyAndNullBatchReturnZero() {
        assertEquals(0, submitService().submitBatch("B", new ArrayList<TMgisSubmitRow>()));
        assertEquals(0, submitService().submitBatch(null, null));
        verify(submitMapper, never()).insert(any());
    }

    @Test
    public void submit_overLimitMinusOneAndNothingInserted() {
        List<TMgisSubmitRow> rows = new ArrayList<TMgisSubmitRow>();
        for (int i = 0; i < 501; i++) {
            TMgisSubmitRow r = new TMgisSubmitRow();
            r.setItemCode("M" + i);
            r.setQty(BigDecimal.ONE);
            rows.add(r);
        }
        assertEquals(-1, submitService().submitBatch("B", rows));
        verify(submitMapper, never()).insert(any());
    }

    @Test
    public void submit_invalidRowSkippedValidRowsBooked() {
        TMgisSubmitRow badCode = new TMgisSubmitRow();
        badCode.setQty(BigDecimal.ONE);
        TMgisSubmitRow badQty = new TMgisSubmitRow();
        badQty.setItemCode("M2");
        badQty.setQty(BigDecimal.ZERO);
        TMgisSubmitRow good = new TMgisSubmitRow();
        good.setItemCode("M3");
        good.setQty(new BigDecimal("2"));
        when(submitMapper.insert(any())).thenReturn(1);
        int ok = submitService().submitBatch("B", Arrays.asList(badCode, badQty, good));
        assertEquals(1, ok);
        // 坏行只记失败明细、好行入账；没有一条坏数据掀翻整批
        verify(submitMapper, times(3)).insert(any());
        assertEquals("B", good.getBatchNo());
        assertEquals(1, good.getStatus().intValue());
        assertEquals(2, badCode.getStatus().intValue());
        assertEquals(1, badCode.getRowNo().intValue(), "保留册内行次（1 起）");
        assertEquals(2, badQty.getRowNo().intValue());
    }

    @Test
    public void submit_duplicateBatchNotInsertedAgain() {
        when(submitMapper.selectCount(any())).thenReturn(2);
        assertEquals(2, submitService().submitBatch("B",
                Collections.singletonList(new TMgisSubmitRow())));
        verify(submitMapper, never()).insert(any());
    }

    // ---------- 建档卡 ----------

    private TMgisScopeCardServiceImpl scopeService() {
        TMgisScopeCardServiceImpl s = new TMgisScopeCardServiceImpl();
        ReflectionTestUtils.setField(s, "baseMapper", scopeMapper);
        ReflectionTestUtils.setField(s, "mgisUnitDocMapper", unitDocMapper);
        return s;
    }

    @Test
    public void scope_listDoesNotForceStatusOrInnerPaging() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TMgisScopeCard> w =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TMgisScopeCard>()
                        .eq("bill_no", "X");
        TMgisScopeCard hit = new TMgisScopeCard();
        hit.setStatus(2);
        when(scopeMapper.selectList(any())).thenReturn(Collections.singletonList(hit));
        List<TMgisScopeCard> list = scopeService().selectTMgisScopeCardList(w);
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).getStatus().intValue(), "清单与详情同口径，不再只露 status=0");
    }

    @Test
    public void scope_insertRejectsCanceledUnit() {
        TMgisUnitDoc canceled = new TMgisUnitDoc();
        canceled.setId(1L);
        canceled.setStatus(1);
        canceled.setDelFlag(0);
        when(unitDocMapper.selectOne(any())).thenReturn(canceled);
        TMgisScopeCard card = new TMgisScopeCard();
        card.setSiteId(1);
        card.setQty(new BigDecimal("5"));
        assertEquals(0, scopeService().insertTMgisScopeCard(card));
        verify(scopeMapper, never()).insert(any());
    }

    @Test
    public void scope_gradeBoundaryTakesUpperTierAndBothPathsAgree() {
        TMgisUnitDoc doc = new TMgisUnitDoc();
        doc.setId(0L);
        doc.setSiteNo("ZZ00");
        doc.setStatus(0);
        doc.setDelFlag(0);
        doc.setTh1Max(new BigDecimal("30"));
        doc.setTh2Max(new BigDecimal("120"));
        doc.setTh3Max(new BigDecimal("360"));
        when(unitDocMapper.selectOne(any())).thenReturn(doc);
        when(scopeMapper.insert(any())).thenReturn(1);
        TMgisScopeCard card = new TMgisScopeCard();
        card.setSiteId(0);
        card.setQty(new BigDecimal("30.00"));
        scopeService().insertTMgisScopeCard(card);
        assertEquals(2, card.getGradeLevel().intValue(), "30 项达一档上限，取高二档");

        // 修改路径同一把尺子
        TMgisScopeCard stored = new TMgisScopeCard();
        stored.setId(9L);
        stored.setSiteId(0);
        stored.setQty(new BigDecimal("30.00"));
        stored.setDelFlag(0);
        when(scopeMapper.selectOne(any())).thenReturn(stored);
        when(scopeMapper.update(any(), any())).thenReturn(1);
        TMgisScopeCard upd = new TMgisScopeCard();
        upd.setId(9L);
        upd.setSiteId(0);
        scopeService().updateTMgisScopeCard(upd);
        assertEquals(2, upd.getGradeLevel().intValue(), "折算与结存两处余钱须对得严");
        assertEquals("ZZ00", upd.getSiteNo());
    }

    @Test
    public void scope_deleteIsSoftDelete() {
        when(scopeMapper.update(any(), any())).thenReturn(1);
        assertEquals(1, scopeService().deleteTMgisScopeCardById(9L));
        verify(scopeMapper, never()).deleteById(any());
    }

    // ---------- 整改回执单 ----------

    private TMgisCheckCardServiceImpl checkService() {
        TMgisCheckCardServiceImpl s = new TMgisCheckCardServiceImpl();
        ReflectionTestUtils.setField(s, "mgisCheckCardMapper", checkMapper);
        return s;
    }

    @Test
    public void check_advanceOneStepAtATimeAndCloseAtTop() {
        TMgisCheckCard r = new TMgisCheckCard();
        r.setId(1L);
        r.setStage(0);
        r.setStatus(0);
        when(checkMapper.selectById(1L)).thenReturn(r);
        when(checkMapper.updateById(any())).thenReturn(1);
        TMgisCheckCard after = checkService().advance(1L, "go");
        assertEquals(1, after.getStage().intValue(), "一次只过一口，不是两口");
        assertEquals(0, after.getStatus().intValue(), "半途仍在办");

        r.setStage(4);
        TMgisCheckCard top = checkService().advance(1L, "go");
        assertEquals(5, top.getStage().intValue());
        assertEquals(1, top.getStatus().intValue(), "到顶关口方销号");
    }

    @Test
    public void check_terminalLockedEverywhere() {
        TMgisCheckCard r = new TMgisCheckCard();
        r.setId(1L);
        r.setStage(5);
        r.setStatus(1);
        when(checkMapper.selectById(1L)).thenReturn(r);
        assertNull(checkService().advance(1L, "go"));
        assertNull(checkService().rollback(1L, "back"));
        assertFalse(checkService().updateContent(1L, "x"));
        assertFalse(checkService().remove(1L));
        verify(checkMapper, never()).updateById(any());
        verify(checkMapper, never()).deleteById(any());
    }

    @Test
    public void check_rollbackOneStepOnly() {
        TMgisCheckCard r = new TMgisCheckCard();
        r.setId(1L);
        r.setStage(3);
        r.setStatus(0);
        when(checkMapper.selectById(1L)).thenReturn(r);
        when(checkMapper.updateById(any())).thenReturn(1);
        TMgisCheckCard after = checkService().rollback(1L, "back");
        assertEquals(2, after.getStage().intValue(), "只退一口，不一退到底");
    }

    // ---------- 逐级核准单 ----------

    private TMgisUseBillServiceImpl useService() {
        TMgisUseBillServiceImpl s = new TMgisUseBillServiceImpl();
        ReflectionTestUtils.setField(s, "mgisUseBillMapper", useBillMapper);
        return s;
    }

    @Test
    public void use_countersignWaitsUntilSignedCountReachesNeed() {
        TMgisUseBill r = new TMgisUseBill();
        r.setId(1L);
        r.setNodeNo(0);
        r.setSignMode(1);
        r.setNeedCount(2);
        r.setSignCount(0);
        r.setStatus(0);
        when(useBillMapper.selectById(1L)).thenReturn(r);
        when(useBillMapper.updateById(any())).thenReturn(1);

        TMgisUseBill first = useService().approve(1L, "u1", "ok");
        assertEquals(0, first.getNodeNo().intValue(), "两名点齐：一票只记账，不进层");
        assertEquals(1, first.getSignCount().intValue());
        assertEquals(0, first.getStatus().intValue());

        TMgisUseBill second = useService().approve(1L, "u2", "ok");
        assertEquals(1, second.getNodeNo().intValue(), "第二票点齐，进上一层");
        assertEquals(0, second.getSignCount().intValue(), "新层签次清零");
    }

    @Test
    public void use_orModeOneVoteAdvancesAndLastNodePasses() {
        TMgisUseBill r = new TMgisUseBill();
        r.setId(1L);
        r.setNodeNo(3);
        r.setSignMode(0);
        r.setSignCount(0);
        r.setStatus(0);
        when(useBillMapper.selectById(1L)).thenReturn(r);
        when(useBillMapper.updateById(any())).thenReturn(1);
        TMgisUseBill done = useService().approve(1L, "boss", "ok");
        assertEquals(3, done.getNodeNo().intValue());
        assertEquals(1, done.getStatus().intValue(), "末层一票即核讫落锁");
    }

    @Test
    public void use_rejectMarksVetoAndFinishedIsLocked() {
        TMgisUseBill r = new TMgisUseBill();
        r.setId(1L);
        r.setNodeNo(1);
        r.setStatus(0);
        when(useBillMapper.selectById(1L)).thenReturn(r);
        when(useBillMapper.updateById(any())).thenReturn(1);
        TMgisUseBill vetoed = useService().reject(1L, "boss", "no");
        assertEquals(2, vetoed.getStatus().intValue(), "否决须落已打回，不能空更新");

        assertNull(useService().approve(1L, "boss", "ok"));
        assertNull(useService().rollback(1L, "back"));
    }

    @Test
    public void use_rollbackClearsSignCountOneNode() {
        TMgisUseBill r = new TMgisUseBill();
        r.setId(1L);
        r.setNodeNo(2);
        r.setSignCount(2);
        r.setStatus(0);
        when(useBillMapper.selectById(1L)).thenReturn(r);
        when(useBillMapper.updateById(any())).thenReturn(1);
        TMgisUseBill back = useService().rollback(1L, "back");
        assertEquals(1, back.getNodeNo().intValue());
        assertEquals(0, back.getSignCount().intValue());
    }
}
