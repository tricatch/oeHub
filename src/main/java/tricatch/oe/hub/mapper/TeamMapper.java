package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.Team;

import java.time.LocalDateTime;
import java.util.List;

public interface TeamMapper {

    String COLS = "team_no, ws_no, team_name, created_by, updated_by, create_at, updated_at";

    // Team-management screen list (cloudGroupService design doc §2.9) - alphabetical, since teams
    // have no other natural ordering (no sort_order column, unlike UA/URL presets).
    @Select("SELECT " + COLS + " FROM HUB_TEAM WHERE ws_no = #{wsNo} ORDER BY team_name")
    List<Team> findByWsNo(Long wsNo);

    @Select("SELECT " + COLS + " FROM HUB_TEAM WHERE team_no = #{teamNo}")
    Team findByTeamNo(Long teamNo);

    @Insert("INSERT INTO HUB_TEAM (ws_no, team_name, created_by, updated_by, create_at, updated_at) "
        + "VALUES (#{wsNo}, #{teamName}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "teamNo")
    void insert(Team team);

    @Update("UPDATE HUB_TEAM SET team_name = #{teamName}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE team_no = #{teamNo}")
    void updateName(@Param("teamNo") Long teamNo, @Param("teamName") String teamName,
                     @Param("updatedBy") Long updatedBy, @Param("updatedAt") LocalDateTime updatedAt);

    // Pre-delete check the caller uses to decide 409 vs. proceeding - see delete()'s javadoc for
    // why this can't just be folded into that one statement.
    @Select("SELECT COUNT(*) FROM HUB_USR WHERE team_no = #{teamNo}")
    int countMembers(Long teamNo);

    // HUB_WS_INVITE.team_no carries a real FK to this table (unlike created_by/updated_by
    // elsewhere, which are deliberately soft references) - a used or expired invite that once
    // pre-assigned this team would otherwise block delete() below with a constraint violation even
    // though it has no bearing on any current member. Only ever called once countMembers() above
    // has already confirmed the team is otherwise safe to delete (see apiDeleteTeam) - nulling this
    // out unconditionally first would incorrectly strip an invite's team_no even when the team ends
    // up NOT being deleted (e.g. a race against a brand-new member assignment).
    @Update("UPDATE HUB_WS_INVITE SET team_no = NULL WHERE team_no = #{teamNo}")
    void clearFromInvites(Long teamNo);

    // Deletion choice: refuse while any member still references this team, rather than nulling
    // HUB_USR.team_no out from under them - a ws_adm who wants to disband a team should reassign
    // its members first, so the member list is never silently changed by a delete elsewhere. The
    // NOT EXISTS guard also keeps this atomic (no separate "is it in use" check that could race
    // with a concurrent team assignment) and doubles as the wsNo scope check (mirrors the
    // wsNo-scoped subquery pattern in HostsProfMapper.updateWrappedContentKeyForRotation) so a
    // forged foreign team_no can't be deleted even if guessed. 0 rows affected means either the
    // team doesn't belong to this workspace or it still has members - the caller (which already
    // verified the team belongs to the caller's workspace before calling this) treats 0 as "still
    // in use".
    @Delete("""
        DELETE FROM HUB_TEAM
        WHERE team_no = #{teamNo}
          AND ws_no = #{wsNo}
          AND NOT EXISTS (SELECT 1 FROM HUB_USR u WHERE u.team_no = HUB_TEAM.team_no)
        """)
    int delete(@Param("teamNo") Long teamNo, @Param("wsNo") Long wsNo);
}
