package edu.rpi.aris.assign.client.gui;

import edu.rpi.aris.assign.GradingStatus;
import edu.rpi.aris.assign.NetUtil;
import edu.rpi.aris.assign.message.MsgUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Button;

import java.util.ArrayList;
import java.util.Comparator;

public class ProblemInfo extends AssignmentInfo {

    private final int problemId;
    private final String createdBy;
    private final long createdOn;
    private final boolean isInstructor;
    private SimpleStringProperty name;
    private ArrayList<SubmissionInfo> children = new ArrayList<>();
    private GradingStatus gradingStatus = GradingStatus.NONE;
    private String status;
    private String date;
    private Button btn;
    private long timestamp;

    public ProblemInfo(MsgUtil.ProblemInfo info, boolean isInstructor) {
        this(info.pid, info.name, info.createdBy, NetUtil.UTCToMilli(info.createdDateUTC), isInstructor);
    }

    public ProblemInfo(int problemId, String name, String createdBy, long createdOn, boolean isInstructor) {
        this.problemId = problemId;
        this.name = new SimpleStringProperty(name);
        this.createdBy = createdBy;
        this.createdOn = createdOn;
        this.isInstructor = isInstructor;
        btn = new Button("Open Template");
    }

    @Override
    public Object getColumnData(int columnNum) {
        switch (columnNum) {
            case 0:
                return name;
            case 1:
                return status;
            case 2:
                return date;
            case 3:
                return btn;
            default:
                return null;
        }
    }

    @Override
    public void addChild(AssignmentInfo info) {
        if (!(info instanceof SubmissionInfo))
            return;
        children.add((SubmissionInfo) info);
        SubmissionInfo subInfo = children.stream().min(Comparator.comparing(SubmissionInfo::getGradingStatus)).orElse(null);
        gradingStatus = subInfo == null ? GradingStatus.NONE : subInfo.getGradingStatus();
        status = subInfo == null ? NetUtil.STATUS_NO_SUBMISSION : subInfo.getStatus();
        date = subInfo == null ? null : subInfo.getDate();
        timestamp = subInfo == null ? -1 : subInfo.getTimestamp();
    }

    @Override
    public ArrayList<SubmissionInfo> getChildren() {
        return children;
    }

    @Override
    public int compareTo(AssignmentInfo o) {
        if (o instanceof ProblemInfo) {
            ProblemInfo p = (ProblemInfo) o;
            return name.get().compareTo(p.name.get());
        } else
            return -1;
    }

    @Override
    public String toString() {
        return name.get();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ProblemInfo))
            return false;
        ProblemInfo info = (ProblemInfo) obj;
        return info.problemId == problemId;
    }

    @Override
    public int hashCode() {
        return problemId;
    }

    public GradingStatus getGradingStatus() {
        return gradingStatus;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public SimpleStringProperty nameProperty() {
        return name;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedOn() {
        return createdOn;
    }

    public int getProblemId() {
        return problemId;
    }
}
