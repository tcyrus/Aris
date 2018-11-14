package edu.rpi.aris.assign.message;

import edu.rpi.aris.assign.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

public class SubmissionRefresh extends Message {

    private HashSet<Integer> subsToRefresh = new HashSet<>();
    private HashMap<Integer, MsgUtil.SubmissionInfo> info = new HashMap<>();

    public SubmissionRefresh(Collection<Integer> toRefresh) {
        super(Perm.SUB_GRADE_REFRESH);
        if (toRefresh != null)
            subsToRefresh.addAll(toRefresh);
    }

    // Do not remove. Needed for gson deserialization
    private SubmissionRefresh() {
        this(null);
    }

    @Override
    public @Nullable ErrorType processMessage(@NotNull Connection connection, @NotNull User user, @NotNull ServerPermissions permissions) throws Exception {
        try (PreparedStatement selectSubmissions = connection.prepareStatement("SELECT id, class_id, assignment_id, time, short_status, status, problem_id, grade FROM submission WHERE user_id = ? AND id = ANY (?);")) {
            selectSubmissions.setInt(1, user.uid);
            Array array = connection.createArrayOf("INTEGER", subsToRefresh.toArray());
            selectSubmissions.setArray(2, array);
            try (ResultSet rs = selectSubmissions.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    int cid = rs.getInt(2);
                    int aid = rs.getInt(3);
                    ZonedDateTime submitted = NetUtil.localToUTC(rs.getTimestamp(4).toLocalDateTime());
                    GradingStatus status;
                    try {
                        status = GradingStatus.valueOf(rs.getString(5));
                    } catch (IllegalArgumentException e) {
                        status = GradingStatus.NONE;
                    }
                    String statusStr = rs.getString(6);
                    int pid = rs.getInt(7);
                    double grade = rs.getDouble(8);
                    MsgUtil.SubmissionInfo sub = new MsgUtil.SubmissionInfo(user.uid, id, pid, cid, aid, grade, status, statusStr, submitted);
                    info.put(id, sub);
                }
            }
        }
        return null;
    }

    @Override
    public @NotNull MessageType getMessageType() {
        return MessageType.REFRESH_SUBMISSION;
    }

    @Override
    public boolean checkValid() {
        return true;
    }

    public HashMap<Integer, MsgUtil.SubmissionInfo> getInfo() {
        return info;
    }
}
