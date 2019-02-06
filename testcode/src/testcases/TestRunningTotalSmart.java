package testcases;

import org.zkoss.zss.model.SSheet;

import java.util.Random;

public class TestRunningTotalSmart implements AsyncTestcase {
    // TODO: fix overflow
    private SSheet _sheet;
    private int _N;
    private int answer;

    public TestRunningTotalSmart(SSheet sheet, int N) {
        _sheet = sheet;
        _N = N;
        answer = 20;

        Random random = new Random(7);

        sheet.setDelayComputation(true);

        sheet.getCell(0, 0).setValue(random.nextInt(1000)+100);
        sheet.getCell(0, 1).setFormulaValue("A1");

        for (int i = 1; i < N; i++) {
            int num = random.nextInt(1000);
            sheet.getCell(i, 0).setValue(num);
            sheet.getCell(i, 1).setFormulaValue("A" + (i+1) + " + B" + (i));
            answer += num;
        }

        sheet.setDelayComputation(false);
    }

    @Override
    public void change() {
        _sheet.getCell(0, 0).setValue(20);
    }

    @Override
    public void touchAll() {
        double something = (double) _sheet.getCell(_N - 1, 1).getValue();
        System.out.println(something);
    }

    @Override
    public boolean verify() {
        try {
            Object value_raw = _sheet.getCell(_N - 1, 1).getValue();
            double value = (double) value_raw;
            return Math.abs(value - answer) <= 1e-6;
        } catch (Exception e) {
            return false;
        }
    }
}
