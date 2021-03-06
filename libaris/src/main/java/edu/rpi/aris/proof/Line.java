package edu.rpi.aris.proof;

import edu.rpi.aris.ast.Expression;
import edu.rpi.aris.rules.RuleList;
import org.apache.commons.lang3.Range;

import java.util.*;
import java.io.ByteArrayOutputStream;

public class Line {

    private final boolean isAssumption;
    private String expressionString = "";
    private Expression expression = null;
    private Claim claim = null;
    private Timer parseTimer = null;
    private Proof proof;
    private LineChangeListener listener;
    private HashSet<Runnable> expressionChangeListeners = new HashSet<>();
    private int lineNumber = -1;
    private HashSet<Line> premises = new HashSet<>();
    private TreeSet<String> constants = new TreeSet<>(Comparator.naturalOrder());
    private int subProofLevel;
    private RuleList selectedRule = null;
    private Proof.Status status = Proof.Status.NONE;
    private boolean underlined;
    private Runnable expressionChangeListener = () -> {
        setStatus(Proof.Status.NONE);
        claim = null;
    };
    private Range<Integer> errorRange = null;
    private String statusMsg = null;
    private StatusChangeListener statusListener;

    Line(int subProofLevel, boolean assumption, Proof proof) {
        isAssumption = assumption;
        this.proof = proof;
        setSubProofLevel(subProofLevel);
        setUnderlined(assumption);
    }

    public void setChangeListener(LineChangeListener listener) {
        this.listener = listener;
    }

    public void setStatusListener(StatusChangeListener statusListener) {
        this.statusListener = statusListener;
    }

    public int getLineNum() {
        return lineNumber;
    }

    public void setLineNum(int lineNumber) {
        this.lineNumber = lineNumber;
        if (listener != null)
            listener.lineNumber(lineNumber);
        proof.modify();
    }

    public int getSubProofLevel() {
        return subProofLevel;
    }

    public void setSubProofLevel(int subProofLevel) {
        this.subProofLevel = subProofLevel;
        if (listener != null)
            listener.subProofLevel(subProofLevel);
        proof.modify();
    }

    public Proof.Status getStatus() {
        return status;
    }

    void setStatus(Proof.Status status) {
        this.status = status;
        if (listener != null)
            listener.status(status);
    }

    public boolean isAssumption() {
        return isAssumption;
    }

    @SuppressWarnings("unchecked")
    public synchronized HashSet<Line> getPremises() {
        return (HashSet<Line>) premises.clone();
    }

    private void onPremiseChange() {
        verifyClaim();
        proof.resetGoalStatus();
        proof.modify();
    }

    synchronized void addPremise(Line premise) {
        premises.add(premise);
        premise.expressionChangeListeners.add(expressionChangeListener);
        if (listener != null)
            listener.premises(premises);
        onPremiseChange();
    }

    synchronized boolean removePremise(Line premise) {
        premise.expressionChangeListeners.remove(expressionChangeListener);
        boolean removed = premises.remove(premise);
        if (removed && listener != null)
            listener.premises(premises);
        onPremiseChange();
        return removed;
    }

    void lineDeleted(Line deletedLine) {
        removePremise(deletedLine);
    }

    public synchronized void buildExpression() {
        String str = getExpressionString();
        if (expression == null) {
            if (str.trim().length() > 0) {
                claim = null;
//                try {
//                    String polish = SentenceUtil.toPolishNotation(str);
//                    try {
                expression = Expression.parseViaRust(str);//new Expression(polish);
//                    } catch (ExpressionParseException e) {
//                        SentenceUtil.mapExceptionToStandardForm(polish, str, e);
//                    }
                if (expression != null) {
                    setStatusString("");
                    setStatus(Proof.Status.NONE);
                    setErrorRange(null);
//                } catch (ExpressionParseException e) {
                } else {
                    setStatusString("Parse Error");
                    setStatus(Proof.Status.INVALID_EXPRESSION);
//                    expression = null;
//                    if (e.getErrorOffset() == -1 || e.getErrorLength() == 0)
//                        setErrorRange(null);
//                    else
//                        setErrorRange(Range.between(e.getErrorOffset(), e.getErrorOffset() + e.getErrorLength() - 1));
//                }
                }
            } else {
                setStatusString("");
                setStatus(Proof.Status.NONE);
                setErrorRange(null);
            }
        }
    }

    public synchronized Expression getExpression() {
        return expression;
    }

    public Premise[] getClaimPremises() {
        Premise[] premises = new Premise[this.getPremises().size()];
        int i = 0;
        for (Line p : this.getPremises()) {
            p.stopTimer();
            p.buildExpression();
            if (p.expression == null) {
                setStatusString("The expression at line " + (p.getLineNum() + 1) + " is invalid");
                setStatus(Proof.Status.INVALID_CLAIM);
//                if (!p.isAssumption || p.getSubProofLevel() != subProofLevel + 1)
                return null;
            }
            if (p.isAssumption && p.getSubProofLevel() == subProofLevel + 1) {
                ArrayList<Line> conclusions = proof.getSubProofConclusions(p, this);
                ArrayList<Expression> exprList = new ArrayList<>();
                for (Line conclusion : conclusions) {
                    conclusion.buildExpression();
                    if (conclusion.expression == null) {
                        setStatusString("The expression at line " + (conclusion.getLineNum() + 1) + " is invalid");
                        setStatus(Proof.Status.INVALID_CLAIM);
                        continue;
                    }
                    exprList.add(conclusion.expression);
                }
                premises[i] = new Premise(p.expression, exprList.toArray(new Expression[0]));
            } else {
                premises[i] = new Premise(p.expression);
            }
            ++i;
        }
        return premises;
    }

    private synchronized void buildClaim() {
        claim = null;
        buildExpression();
        if (expression == null || isAssumption)
            return;
        if (selectedRule == null) {
            setStatusString("Rule Not Specified");
            setStatus(Proof.Status.NO_RULE);
            return;
        }
        if (proof.getAllowedRules().size() > 0 && !proof.getAllowedRules().contains(selectedRule)) {
            setStatusString("The Rule \"" + selectedRule.name + "\" has been restricted for this proof");
            setStatus(Proof.Status.INVALID_CLAIM);
            return;
        }
        Premise[] premises = getClaimPremises();
        if (premises == null)
            return;
        claim = new Claim(expression, premises, selectedRule.rule);
    }

    public boolean verifyClaim() {
        return verifyClaim(true);
    }

    private boolean haveClaimResult(String result) {
        if (result == null) {
            setStatusString("Line is Correct!");
            setStatus(Proof.Status.CORRECT);
        } else {
            setStatusString(result);
            setStatus(Proof.Status.INVALID_CLAIM);
        }
        return result == null;
    }

    private synchronized boolean verifyClaim(boolean stopTimer) {
        try {
            ByteArrayOutputStream boas = new ByteArrayOutputStream();
            new SaveManager(new SaveInfoListener() {
                public boolean notArisFile(String filename, String programName, String programVersion) { return false; }
                public void integrityCheckFailed(String filename) { }
            }).saveProof(proof, boas);
            String xml = boas.toString("utf8");
            if(proof.getNumLines() > 0) {
                RustProof rp = RustProof.fromXml(xml);
                if(rp != null) {
                    if (stopTimer)
                        stopTimer();
                    buildClaim();
                    if(claim != null) {
                        String result = rp.checkRuleAtLine(this.lineNumber);
                        return haveClaimResult(result);
                    }
                } else {
                    System.err.printf("Warning: RustProof.fromXml returned null\n");
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
        if (stopTimer)
            stopTimer();
        buildClaim();
        if (claim != null) {
            String result = claim.isValidClaim();
            haveClaimResult(result);
        }
        return false;
    }

    public String getExpressionString() {
        return expressionString;
    }

    public void setExpressionString(String expressionString) {
        setExpressionString(expressionString, false);
    }

    public void setExpressionString(String expressionString, boolean buildImmediately) {
        this.proof.rustProof.setExpressionString(this.lineNumber, expressionString);
        this.expressionString = expressionString;
        synchronized (Line.this) {
            expression = null;
            claim = null;
            startTimer();
            proof.resetGoalStatus();
            setStatus(Proof.Status.NONE);
        }
        if (listener != null)
            listener.expressionString(expressionString);
        for (Runnable r : expressionChangeListeners)
            r.run();
        if (buildImmediately)
            buildExpression();
        proof.modify();
    }

    public boolean isUnderlined() {
        return underlined;
    }

    public void setUnderlined(boolean underlined) {
        this.proof.rustProof.moveCursor(this.lineNumber);
        this.underlined = underlined;
        if (listener != null)
            listener.underlined(underlined);
    }

    public RuleList getSelectedRule() {
        return selectedRule;
    }

    public void setSelectedRule(RuleList rule) {
        this.selectedRule = rule;
        if (listener != null)
            listener.selectedRule(rule);
        verifyClaim();
        proof.resetGoalStatus();
        proof.modify();
    }

    public String getStatusString() {
        return statusMsg;
    }

    private void setStatusString(String status) {
        statusMsg = status;
        if (listener != null)
            listener.statusString(status);
        if (statusListener != null)
            statusListener.statusString(this, status);
    }

    private synchronized void startTimer() {
        stopTimer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                synchronized (Line.this) {
                    parseTimer = null;
                    verifyClaim(false);
                }
            }
        };
        parseTimer = new Timer(true);
        parseTimer.schedule(task, 1000);
    }

    private synchronized void stopTimer() {
        if (parseTimer != null) {
            parseTimer.cancel();
            parseTimer = null;
        }
    }

    public Range<Integer> getErrorRange() {
        return errorRange;
    }

    private void setErrorRange(Range<Integer> range) {
        this.errorRange = range;
        if (listener != null)
            listener.errorRange(range);
        if (statusListener != null)
            statusListener.errorRange(this, range);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Line))
            return false;
        Line l = (Line) obj;
        if (expression == null)
            buildExpression();
        if (l.expression == null)
            l.buildExpression();
        if (expression == null || l.expression == null)
            return false;
        else
            return expression.equals(l.expression);
    }

    public TreeSet<String> getConstants() {
        return constants;
    }
}
