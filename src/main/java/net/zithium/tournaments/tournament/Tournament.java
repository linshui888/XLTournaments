/*
 * XLTournaments Plugin
 * Copyright (c) 2020 - 2023  Zithium Studios. All rights reserved.
 */

package net.zithium.tournaments.tournament;

import net.zithium.tournaments.XLTournamentsPlugin;
import net.zithium.tournaments.action.ActionManager;
import net.zithium.tournaments.events.CompletedChallengeEvent;
import net.zithium.tournaments.events.TournamentEndEvent;
import net.zithium.tournaments.events.TournamentStartEvent;
import net.zithium.tournaments.objective.XLObjective;
import net.zithium.tournaments.storage.StorageHandler;
import net.zithium.tournaments.utility.TimeUtil;
import net.zithium.tournaments.utility.Timeline;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class Tournament {

    private final XLTournamentsPlugin plugin;
    private final ActionManager actionManager;
    private final StorageHandler storageHandler;
    private final String identifier;

    private UUID gameUniqueId;
    private BukkitTask updateTask;
    private TournamentStatus status;
    private ZonedDateTime startDate, endDate;
    private long startTimeMillis, endTimeMillis;
    private ZoneId zoneId;
    private boolean updating;
    private XLObjective objective;
    private Timeline timeline;
    private int leaderboardRefresh;
    private List<String> disabledWorlds;
    private List<GameMode> disabledGamemodes;
    private Map<Integer, List<String>> rewards;
    private boolean challenge;
    private int challengeGoal;
    private List<String> startActions, endActions;

    private boolean automaticParticipation;
    private Permission participationPermission;
    private double participationCost;
    private List<String> participationActions;
    private final Map<UUID, Integer> participants;
    private Map<UUID, Integer> sortedParticipants;

    private final Map<String, Object> meta;

    Tournament(XLTournamentsPlugin plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
        this.actionManager = plugin.getActionManager();
        this.storageHandler = plugin.getStorageManager().getStorageHandler();
        this.participants = new ConcurrentHashMap<>();
        this.sortedParticipants = new LinkedHashMap<>();
        this.challenge = false;
        this.challengeGoal = -1;
        this.leaderboardRefresh = 60;
        this.automaticParticipation = false;
        this.updating = false;
        this.participationCost = 0.0;
        this.participationActions = new ArrayList<>();
        this.disabledWorlds = new ArrayList<>();
        this.disabledGamemodes = new ArrayList<>();
        this.rewards = new HashMap<>();
        this.startActions = new ArrayList<>();
        this.endActions = new ArrayList<>();
        this.meta = new HashMap<>();
    }

    public void updateStatus() {
        if (timeline != Timeline.SPECIFIC) {
            startDate = TimeUtil.getStartTime(timeline, zoneId);
            endDate = TimeUtil.getEndTime(timeline, zoneId);
        }

        startTimeMillis = startDate.toInstant().toEpochMilli();
        endTimeMillis = endDate.toInstant().toEpochMilli();

        if (TimeUtil.isWaiting(startDate)) {
            status = TournamentStatus.WAITING;
        } else if (!TimeUtil.isWaiting(startDate) && !TimeUtil.isEnded(endDate)) {
            status = TournamentStatus.ACTIVE;
        } else if (TimeUtil.isEnded(endDate)) {
            status = TournamentStatus.ENDED;
        }
    }

    /**
     * Starts the tournament, initializing necessary tasks and triggering associated actions.
     *
     * @param clearParticipants Should all tournament participants be cleared when this method is called.
     */
    public void start(boolean clearParticipants) {
        if (debug()) plugin.getLogger().log(Level.INFO, "Executing tournament start.");
        // If it's the first time, asynchronously clear participants.
        if (clearParticipants) {
            if (debug()) plugin.getLogger().log(Level.INFO, "Clearing tournament participants.");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::clearParticipants);

            // If there are start actions defined, execute them for all online players.
            if (!startActions.isEmpty()) {
                if (debug()) plugin.getLogger().log(Level.INFO, "Executing start actions.");
                Bukkit.getScheduler().runTask(plugin, () -> actionManager.executeActions(null, startActions));
            }
        }

        // Set the tournament status to ACTIVE.
        status = TournamentStatus.ACTIVE;

        gameUniqueId = UUID.randomUUID();

        // Schedule a task to periodically update the tournament (asynchronously).
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            // If not already updating, perform the update.
            if (!isUpdating()) update();
        }, 0, leaderboardRefresh * 20L);

        // Trigger a TournamentStartEvent to notify other plugins.
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.getPluginManager().callEvent(new TournamentStartEvent(this))
        );
        if (debug()) plugin.getLogger().log(Level.INFO, "Tournament has been started.");
    }


    /**
     * Stops the tournament if it is currently active. This method cancels any ongoing update tasks,
     * processes rewards for participants, triggers the TournamentEndEvent if applicable,
     * executes end actions, and clears all participant data.
     * If the tournament is not in an active state, this method does nothing.
     *
     * @throws IllegalStateException if the tournament is in an invalid state for stopping.
     * @see TournamentEndEvent
     */
    public void stop() {
        if (debug()) plugin.getLogger().log(Level.INFO, "Executing tournament stop.");
        if (status != TournamentStatus.ACTIVE) throw new IllegalStateException("Attempted to stop a Tournament that is not ACTIVE");
        status = TournamentStatus.ENDED;
        
        if (updateTask != null) updateTask.cancel();
        update();

        Bukkit.getPluginManager().callEvent(new TournamentEndEvent(this, new TournamentData(identifier, gameUniqueId, new LinkedHashMap<>(sortedParticipants))));

        if (challenge) return;

        for (int position : rewards.keySet()) {
            OfflinePlayer player = getPlayerFromPosition(position);
            if (player == null) continue;
            if (player.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> actionManager.executeActions(player.getPlayer(), rewards.get(position)));
                if (debug()) plugin.getLogger().log(Level.INFO, "Executed end actions for " + player.getName() + "(" + player.getUniqueId() + ")");
                continue;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (String action : rewards.get(position)) {
                    storageHandler.addActionToQueue(player.getUniqueId().toString(), action);
                    if (debug()) plugin.getLogger().log(Level.INFO, "Queued end actions for " + player.getName() + "(" + player.getUniqueId() + ")");
                }
            });
        }

        if (!endActions.isEmpty()) {
            if (debug()) plugin.getLogger().log(Level.INFO, "Executing end actions.");
            Bukkit.getScheduler().runTask(plugin, () -> actionManager.executeActions(null, endActions));
        }
        if (debug()) plugin.getLogger().log(Level.INFO, "Tournament has been stopped.");

        clearParticipants();
    }

    /**
     * Updates the tournament's participant information, including their scores or rankings,
     * and refreshes the list of sorted participants based on the updated data.
     * This method sets a flag to prevent concurrent updates while it's running.
     * It should be called periodically to ensure accurate participant data.
     */
    public void update() {
        updating = true;

        sortedParticipants.clear();
        for (Map.Entry<UUID, Integer> entry : participants.entrySet()) {
            storageHandler.updateParticipant(getIdentifier(), entry.getKey(), entry.getValue());
        }

        sortedParticipants = storageHandler.getTopPlayers(identifier);
        updating = false;
    }

    /**
     * Clears all participants from the target tournament.
     */
    public void clearParticipants() {
        if (debug()) plugin.getLogger().log(Level.INFO, "Clearing participants for", identifier);
        participants.clear();
        sortedParticipants.clear();
        storageHandler.clearParticipants(identifier);
    }

    public void clearParticipant(UUID uuid) {
        participants.remove(uuid);
        sortedParticipants.remove(uuid);
        storageHandler.clearParticipant(identifier, uuid);
    }

    // Start of TournamentFactory methods //
    void setObjective(XLObjective objective) {
        this.objective = objective;
    }

    void setChallenge(boolean challenge) {
        this.challenge = challenge;
    }

    void setChallengeGoal(int amount) {
        this.challengeGoal = amount;
    }

    void setTimeline(Timeline timeline) {
        this.timeline = timeline;
    }

    void setStartDate(ZonedDateTime startDate) {
        this.startDate = startDate;
    }

    void setEndDate(ZonedDateTime endDate) {
        this.endDate = endDate;
    }

    void setUpdateTime(int time) {
        if (time < 10) time = 10;
        this.leaderboardRefresh = time;
    }

    void setAutomaticParticipation(boolean value) {
        this.automaticParticipation = value;
    }

    void setParticipationCost(double value) {
        this.participationCost = value;
    }

    void setParticipationPermission(Permission participationPermission) {
        this.participationPermission = participationPermission;
    }

    void setParticipationActions(List<String> actions) {
        this.participationActions = actions;
    }

    void setDisabledWorlds(List<String> worlds) {
        this.disabledWorlds = worlds;
    }

    void setDisabledGamemodes(List<GameMode> gamemodes) {
        this.disabledGamemodes = gamemodes;
    }

    void setRewards(Map<Integer, List<String>> rewards) {
        this.rewards = rewards;
    }

    void setStartActions(List<String> startActions) {
        this.startActions = startActions;
    }

    void setEndActions(List<String> endActions) {
        this.endActions = endActions;
    }

    void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    // End of TournamentFactory methods //

    public String getTimeRemaining() {
        if (status == TournamentStatus.ACTIVE) {
            return TimeUtil.getStringBetweenDates(endDate);
        } else if (status == TournamentStatus.WAITING) {
            return TimeUtil.getStringBetweenDates(startDate);
        }
        return "N/A";
    }

    public int getPosition(UUID uuid) {
        if (sortedParticipants.containsKey(uuid)) {
            return (new ArrayList<>(sortedParticipants.keySet())).indexOf(uuid) + 1;
        }
        return 0;
    }

    public int getScore(UUID uuid) {
        return participants.getOrDefault(uuid, 0);
    }

    /**
     * Gets the OfflinePlayer associated with the specified position in the tournament.
     *
     * @param position The position in the tournament (1 for first place, 2 for second, etc.).
     * @return The OfflinePlayer associated with the given position or null if not found.
     */
    public OfflinePlayer getPlayerFromPosition(int position) {
        if (position < 1 || position > sortedParticipants.size()) return null;
        int count = 1;
        for (UUID uuid : sortedParticipants.keySet()) {
            if (count == position) {
                return Bukkit.getOfflinePlayer(uuid);
            }
            count++;
        }
        return null;
    }

    /**
     * Retrieves the score of the participant at the specified position in the sorted list of participants.
     * The position should be within the valid range of 1 to the size of the sorted participant list.
     *
     * @param position The position of the participant in the sorted list.
     * @return The score of the participant at the given position, or 0 if the position is invalid or the score is non-positive.
     */
    public int getScoreFromPosition(int position) {
        // Check if the specified position is out of bounds or non-positive.
        if (position < 1 || position > sortedParticipants.size()) {
            return 0;
        }

        // Get the UUID of the participant at the specified position.
        UUID uuid = (UUID) sortedParticipants.keySet().toArray()[position - 1];

        // Get the score of the participant at the specified position.
        int score = sortedParticipants.get(uuid);

        // Return the score (positive or 0).
        return Math.max(0, score);
    }

    /**
     * Adds a tournament participant.
     *
     * @param uuid           The target's uuid
     * @param score          The score to add to the target
     * @param insertDatabase Insert into the database true/false
     */

    public void addParticipant(UUID uuid, int score, boolean insertDatabase) {
        if (debug()) plugin.getLogger().log(Level.INFO, "[DEBUG] Adding " + uuid + " to a tournament");

        synchronized (participants) {
            participants.put(uuid, score);
        }
        if (insertDatabase) {
            storageHandler.addParticipant(getIdentifier(), uuid);
        }
    }

    public void addScore(UUID uuid, int amount) {
        addScore(uuid, amount, false);
    }

    public void addScore(UUID uuid, int amount, boolean replace) {
        if (participants.containsKey(uuid)) {
            participants.put(uuid, replace ? amount : participants.get(uuid) + amount);
        } else {
            participants.put(uuid, amount);
        }

        if (hasFinishedChallenge(uuid)) {
            storageHandler.updateParticipant(getIdentifier(), uuid, participants.get(uuid));

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int position = getPlayersCompletedChallenge();
                Player player = Bukkit.getPlayer(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (rewards.containsKey(position)) {
                        actionManager.executeActions(player, rewards.get(position));
                    }

                    Bukkit.getPluginManager().callEvent(new CompletedChallengeEvent(player, position, this));
                });
            });
        }
    }

    public int getPlayersCompletedChallenge() {
        return storageHandler.getTopPlayersByScore(identifier, challengeGoal).size();
    }

    public String getStartDay() {
        return TimeUtil.getDay(startDate);
    }

    public String getEndDay() {
        return TimeUtil.getDay(endDate);
    }

    public String getStartMonth() {
        return TimeUtil.getMonthName(startDate);
    }

    public String getStartMonthNumber() {
        return TimeUtil.getMonthNumber(startDate);
    }

    public String getEndMonthNumber() {
        return TimeUtil.getMonthNumber(endDate);
    }

    public String getEndMonth() {
        return TimeUtil.getMonthName(endDate);
    }

    public boolean isParticipant(UUID uuid) {
        return participants.containsKey(uuid);
    }

    public Map<UUID, Integer> getParticipants() {
        return participants;
    }

    public boolean hasFinishedChallenge(UUID uuid) {
        return challenge && participants.get(uuid) >= challengeGoal;
    }

    public void removeParticipant(UUID uuid) {
        participants.remove(uuid);
    }

    public Map<UUID, Integer> getSortedParticipants() {
        return sortedParticipants;
    }

    public double getParticipationCost() {
        return participationCost;
    }

    public Permission getParticipationPermission() {
        return participationPermission;
    }

    public boolean isAutomaticParticipation() {
        return automaticParticipation;
    }

    public List<String> getParticipationActions() {
        return participationActions;
    }

    public String getIdentifier() {
        return identifier;
    }

    public boolean isChallenge() {
        return challenge;
    }

    public int getChallengeGoal() {
        return challengeGoal;
    }

    public XLObjective getObjective() {
        return objective;
    }

    public Timeline getTimeline() {
        return timeline;
    }

    public int getLeaderboardRefresh() {
        return leaderboardRefresh;
    }

    public TournamentStatus getStatus() {
        return status;
    }

    public List<String> getDisabledWorlds() {
        return disabledWorlds;
    }

    public List<GameMode> getDisabledGamemodes() {
        return disabledGamemodes;
    }

    public Object getMeta(String identifier) {
        return meta.get(identifier);
    }

    public void setMeta(String identifier, Object data) {
        meta.put(identifier, data);
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public boolean hasMeta(String identifier) {
        return meta.containsKey(identifier);
    }

    public boolean isUpdating() {
        return updating;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public ZonedDateTime getStartDate() {
        return startDate;
    }

    public ZonedDateTime getEndDate() {
        return endDate;
    }

    public BukkitTask getUpdateTask() {
        return updateTask;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public void setStatus(TournamentStatus status) {
        this.status = status;
    }

    public boolean debug() {
        return XLTournamentsPlugin.isDebugMode();
    }
}
