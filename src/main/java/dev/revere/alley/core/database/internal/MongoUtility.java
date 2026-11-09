package dev.revere.alley.core.database.internal;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.feature.cosmetic.model.CosmeticType;
import dev.revere.alley.feature.bot.CustomBotProfile;
import dev.revere.alley.feature.division.Division;
import dev.revere.alley.feature.division.DivisionService;
import dev.revere.alley.feature.layout.data.LayoutData;
import dev.revere.alley.feature.music.MusicService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.data.ProfileData;
import dev.revere.alley.core.profile.data.types.*;
import dev.revere.alley.core.profile.enums.ChatChannel;
import dev.revere.alley.core.profile.enums.WorldTime;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.serializer.Serializer;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.bson.Document;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author Remi
 * @project Alley
 * @date 5/26/2024
 *
 * MongoDB 工具类，提供 Profile 对象与 MongoDB Document 之间的序列化/反序列化功能。
 * MongoDB utility class providing serialization/deserialization between Profile objects and MongoDB Documents.
 */
@UtilityClass
public class MongoUtility {
    private static final String EMPTY_STRING = "";
    private static final int DEFAULT_ELO = 1000;
    private static final int DEFAULT_COINS = 100;
    private static final int DEFAULT_INT = 0;
    private static final long DEFAULT_LONG = 0L;
    private static final boolean DEFAULT_BOOLEAN_TRUE = true;
    private static final boolean DEFAULT_BOOLEAN_FALSE = false;

    /**
     * Represents the result of a validation operation.
     *
     * 表示验证操作的结果。
     */
    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        private ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(List<String> errors) {
            return new ValidationResult(false, errors);
        }

        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }
    }

    /**
     * Validates a Profile object with comprehensive checks.
     *
     * 对 Profile 对象进行全面的验证检查。
     *
     * @param profile The Profile object to validate.
     *                要验证的 Profile 对象。
     * @return A ValidationResult indicating whether the profile is valid or not.
     *         表示 Profile 是否有效的 ValidationResult。
     */
    public ValidationResult validateProfile(Profile profile) {
        List<String> errors = new ArrayList<>();

        if (profile == null) {
            errors.add("Profile cannot be null");
            return ValidationResult.invalid(errors);
        }

        if (profile.getUuid() == null) {
            errors.add("Profile UUID cannot be null");
        }

        if (profile.getName() == null || profile.getName().trim().isEmpty()) {
            errors.add("Profile name cannot be null or empty");
        }

        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    /**
     * Converts a Profile object to a MongoDB Document with comprehensive validation and null safety.
     *
     * 将 Profile 对象转换为 MongoDB Document，包含全面的验证和空安全处理。
     *
     * @param profile The Profile object to convert.
     *                要转换的 Profile 对象。
     * @return A Document representation of the Profile.
     *         Profile 的 Document 表示形式。
     * @throws IllegalArgumentException if the profile is invalid.
     *                                  如果 Profile 无效。
     */
    public Document toDocument(Profile profile) {
        ValidationResult validation = validateProfile(profile);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Profile validation failed: " + validation.getErrors());
        }

        try {
            Document document = new Document();
            document.put("firstJoin", profile.getFirstJoin());
            document.put("uuid", profile.getUuid().toString());
            document.put("name", safeString(profile.getName()));

            ProfileData profileData = profile.getProfileData();
            if (profileData != null) {
                document.put("profileData", convertProfileData(profileData));
            } else {
                Logger.warn(String.format("ProfileData is null for profile: %s, using empty document", profile.getUuid()));
                document.put("profileData", new Document());
            }

            return document;
        } catch (Exception e) {
            Logger.logException(String.format("Failed to convert profile to document for UUID: %s", profile.getUuid()), e);
            throw new RuntimeException("Profile to document conversion failed", e);
        }
    }

    /**
     * Updates a Profile object from a MongoDB Document with comprehensive error handling.
     *
     * 从 MongoDB Document 更新 Profile 对象，包含全面的错误处理。
     *
     * @param profile  The Profile object to update.
     *                 要更新的 Profile 对象。
     * @param document The Document containing the profile data.
     *                 包含 Profile 数据的 Document。
     * @throws IllegalArgumentException if the profile or document is null.
     *                                  如果 Profile 或 Document 为 null。
     */
    public void updateProfileFromDocument(Profile profile, Document document) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }

        try {
            Long firstJoin = document.getLong("firstJoin");
            if (firstJoin != null) {
                profile.setFirstJoin(firstJoin);
            }

            Document profileDataDocument = document.get("profileData", Document.class);
            if (profileDataDocument != null) {
                ProfileData profileData = parseProfileData(profileDataDocument);
                profile.setProfileData(profileData);
            } else {
                Logger.warn(String.format("ProfileData document is null for profile: %s, creating new ProfileData", profile.getUuid()));
                profile.setProfileData(new ProfileData());
            }
        } catch (Exception e) {
            Logger.logException(String.format("Error updating profile from document for UUID: %s", profile.getUuid()), e);
            throw new RuntimeException("Failed to update profile from document", e);
        }
    }

    /**
     * Converts ProfileData to a MongoDB Document with comprehensive null safety.
     *
     * 将 ProfileData 转换为 MongoDB Document，包含全面的空安全处理。
     *
     * @param profileData The ProfileData object to convert.
     *                    要转换的 ProfileData 对象。
     * @return A Document representation of the ProfileData.
     *         ProfileData 的 Document 表示形式。
     */
    private static Document convertProfileData(ProfileData profileData) {
        return new DocumentBuilder()
                .put("elo", profileData.getElo())
                .put("coins", profileData.getCoins())
                .put("unrankedWins", profileData.getUnrankedWins())
                .put("unrankedLosses", profileData.getUnrankedLosses())
                .put("rankedWins", profileData.getRankedWins())
                .put("rankedLosses", profileData.getRankedLosses())
                .put("rankedBanned", profileData.isRankedBanned())
                .put("rankedBanExpiry", profileData.getRankedBanExpiry())
                .put("rankedBanReason", safeString(profileData.getRankedBanReason()))
                .put("rankedBanId", safeString(profileData.getRankedBanId()))
                .put("tournamentWins", profileData.getTournamentWins())
                .put("tournamentLosses", profileData.getTournamentLosses())
                .put("tournamentParticipated", profileData.isTournamentParticipated())
                .put("globalLevel", safeString(profileData.getGlobalLevel()))
                .put("selectedTitle", safeString(profileData.getSelectedTitle()))
                .put("unlockedTitles", safeList(profileData.getUnlockedTitles()))
                .putSafe("unrankedKitData", profileData::getUnrankedKitData, MongoUtility::convertUnrankedKitData)
                .putSafe("rankedKitData", profileData::getRankedKitData, MongoUtility::convertRankedKitData)
                .putSafe("ffaData", profileData::getFfaData, MongoUtility::convertFFAData)
                .putSafe("tournamentKitWins", profileData::getTournamentKitWins, MongoUtility::convertTournamentKitWins)
                .putSafe("layoutData", profileData::getLayoutData, MongoUtility::convertLayoutData)
                .putSafe("settingData", profileData::getSettingData, MongoUtility::convertProfileSettingData)
                .putSafe("cosmeticData", profileData::getCosmeticData, MongoUtility::convertProfileCosmeticData)
                .putSafe("playTimeData", profileData::getPlayTimeData, MongoUtility::convertProfilePlayTimeData)
                .putSafe("musicData", profileData::getMusicData, MongoUtility::convertProfileMusicData)
                .putSafe("challengeData", profileData::getChallengeData, MongoUtility::convertProfileChallengeData)
                .putSafe("customBotProfile", profileData::getCustomBotProfile, MongoUtility::convertCustomBotProfile)
                .build();
    }

    /**
     * Parses a MongoDB Document into a ProfileData object with comprehensive null safety.
     *
     * 将 MongoDB Document 解析为 ProfileData 对象，包含全面的空安全处理。
     *
     * @param profileDataDocument The Document containing the profile data.
     *                            包含 Profile 数据的 Document。
     * @return A ProfileData object populated with the data from the Document.
     *         使用 Document 中的数据填充的 ProfileData 对象。
     */
    private static ProfileData parseProfileData(Document profileDataDocument) {
        ProfileData profileData = new ProfileData();

        profileData.setElo(profileDataDocument.getInteger("elo", DEFAULT_ELO));
        profileData.setCoins(profileDataDocument.getInteger("coins", DEFAULT_COINS));
        profileData.setUnrankedWins(profileDataDocument.getInteger("unrankedWins", DEFAULT_INT));
        profileData.setUnrankedLosses(profileDataDocument.getInteger("unrankedLosses", DEFAULT_INT));
        profileData.setRankedWins(profileDataDocument.getInteger("rankedWins", DEFAULT_INT));
        profileData.setRankedLosses(profileDataDocument.getInteger("rankedLosses", DEFAULT_INT));
        profileData.setRankedBanned(profileDataDocument.getBoolean("rankedBanned", DEFAULT_BOOLEAN_FALSE));
        Long expiry = profileDataDocument.getLong("rankedBanExpiry");
        profileData.setRankedBanExpiry(expiry != null ? expiry : 0L);
        profileData.setRankedBanReason(profileDataDocument.getString("rankedBanReason"));
        profileData.setRankedBanId(profileDataDocument.getString("rankedBanId"));
        profileData.setTournamentWins(profileDataDocument.getInteger("tournamentWins", DEFAULT_INT));
        profileData.setTournamentLosses(profileDataDocument.getInteger("tournamentLosses", DEFAULT_INT));
        profileData.setTournamentParticipated(profileDataDocument.getBoolean("tournamentParticipated", DEFAULT_BOOLEAN_FALSE));
        profileData.setGlobalLevel(profileDataDocument.getString("globalLevel"));
        profileData.setSelectedTitle(profileDataDocument.getString("selectedTitle"));

        List<String> unlockedTitles = profileDataDocument.getList("unlockedTitles", String.class);
        if (unlockedTitles != null) {
            profileData.setUnlockedTitles(new ArrayList<>(unlockedTitles));
        }

        parseAndMerge(profileDataDocument, "unrankedKitData", MongoUtility::parseUnrankedKitData,
                profileData.getUnrankedKitData(), profileData::setUnrankedKitData);
        parseAndMerge(profileDataDocument, "rankedKitData", MongoUtility::parseRankedKitData,
                profileData.getRankedKitData(), profileData::setRankedKitData);
        parseAndMerge(profileDataDocument, "ffaData", MongoUtility::parseFFAData,
                profileData.getFfaData(), profileData::setFfaData);

        Document tournamentKitWinsDoc = profileDataDocument.get("tournamentKitWins", Document.class);
        if (tournamentKitWinsDoc != null) {
            tournamentKitWinsDoc.forEach((kitName, count) ->
                    profileData.getTournamentKitWins().put(kitName, tournamentKitWinsDoc.getInteger(kitName, DEFAULT_INT)));
        }

        parseAndSet(profileDataDocument, "layoutData", MongoUtility::parseProfileLayoutData,
                profileData::setLayoutData, ProfileLayoutData::new);
        parseAndSet(profileDataDocument, "settingData", MongoUtility::parseProfileSettingData,
                profileData::setSettingData, ProfileSettingData::new);
        parseAndSet(profileDataDocument, "cosmeticData", MongoUtility::parseProfileCosmeticData,
                profileData::setCosmeticData, ProfileCosmeticData::new);
        parseAndSet(profileDataDocument, "playTimeData", MongoUtility::parseProfilePlayTimeData,
                profileData::setPlayTimeData, ProfilePlayTimeData::new);
        parseAndSet(profileDataDocument, "musicData", MongoUtility::parseProfileMusicData,
                profileData::setMusicData, MongoUtility::createDefaultMusicData);
        parseAndSet(profileDataDocument, "challengeData", MongoUtility::parseProfileChallengeData,
                profileData::setChallengeData, ProfileChallengeData::new);
        parseAndSet(profileDataDocument, "customBotProfile", MongoUtility::parseCustomBotProfile,
                profileData::setCustomBotProfile, CustomBotProfile::new);

        // Division progression now counts wins from every mode, so recompute the stored
        // division/tier from the combined win counts on load.
        // 段位进度现在统计所有模式的胜场，因此在加载时根据总胜场重新计算存储的段位/层级。
        profileData.refreshAllDivisions();

        return profileData;
    }

    public static Document convertCustomBotProfile(CustomBotProfile custom) {
        if (custom == null) return new Document();
        return new DocumentBuilder()
                .put("name", safeString(custom.getName()))
                .put("skinName", safeString(custom.getSkinName()))
                .put("cps", custom.getCps())
                .put("maxReach", custom.getMaxReach())
                .put("swingRange", custom.getSwingRange())
                .put("minReach", custom.getMinReach())
                .put("combatDistance", custom.getCombatDistance())
                .put("movementSpeed", custom.getMovementSpeed())
                .put("aimSpeed", custom.getAimSpeed())
                .put("aimError", custom.getAimError())
                .put("ping", custom.getPing())
                .put("tryhard", custom.isTryhard())
                .put("wTap", custom.isWTap())
                .put("wTapRate", custom.getWTapRate())
                .put("wTapReactionTimeMs", custom.getWTapReactionTimeMs())
                .put("blockHit", custom.isBlockHit())
                .put("strafe", custom.isStrafe())
                .put("bow", custom.isBow())
                .put("rod", custom.isRod())
                .put("lava", custom.isLava())
                .put("lavaTicks", custom.getLavaTicks())
                .put("antiFire", custom.isAntiFire())
                .put("healHealth", custom.getHealHealth())
                .put("clickJitter", custom.getClickJitter())
                .put("misclickChance", custom.getMisclickChance())
                .put("aimSmoothing", custom.getAimSmoothing())
                .put("switchDelayTicks", custom.getSwitchDelayTicks())
                .put("critChance", custom.getCritChance())
                .put("blockHitRate", custom.getBlockHitRate())
                .build();
    }

    private static CustomBotProfile parseCustomBotProfile(Document document) {
        CustomBotProfile custom = new CustomBotProfile();
        if (document == null) return custom;
        if (document.getString("name") != null) custom.setName(document.getString("name"));
        if (document.getString("skinName") != null) custom.setSkinName(document.getString("skinName"));
        custom.setCps(readDouble(document, "cps", custom.getCps()));
        custom.setMaxReach(readDouble(document, "maxReach", custom.getMaxReach()));
        custom.setSwingRange(readDouble(document, "swingRange", custom.getSwingRange()));
        custom.setMinReach(readDouble(document, "minReach", custom.getMinReach()));
        custom.setCombatDistance(readDouble(document, "combatDistance", custom.getMinReach()));
        custom.setMovementSpeed(readDouble(document, "movementSpeed", custom.getMovementSpeed()));
        custom.setAimSpeed(readDouble(document, "aimSpeed", custom.getAimSpeed()));
        custom.setAimError(readDouble(document, "aimError", custom.getAimError()));
        custom.setPing(document.getInteger("ping", custom.getPing()));
        custom.setTryhard(document.getBoolean("tryhard", custom.isTryhard()));
        custom.setWTap(document.getBoolean("wTap", custom.isWTap()));
        custom.setWTapRate(readDouble(document, "wTapRate", custom.getWTapRate()));
        custom.setWTapReactionTimeMs(document.getInteger("wTapReactionTimeMs", custom.getWTapReactionTimeMs()));
        custom.setBlockHit(document.getBoolean("blockHit", custom.isBlockHit()));
        custom.setStrafe(document.getBoolean("strafe", custom.isStrafe()));
        custom.setBow(document.getBoolean("bow", custom.isBow()));
        custom.setRod(document.getBoolean("rod", custom.isRod()));
        custom.setLava(document.getBoolean("lava", custom.isLava()));
        custom.setLavaTicks(document.getInteger("lavaTicks", custom.getLavaTicks()));
        custom.setAntiFire(document.getBoolean("antiFire", custom.isAntiFire()));
        custom.setHealHealth(readDouble(document, "healHealth", custom.getHealHealth()));
        custom.setClickJitter(readDouble(document, "clickJitter", custom.getClickJitter()));
        custom.setMisclickChance(readDouble(document, "misclickChance", custom.getMisclickChance()));
        custom.setAimSmoothing(readDouble(document, "aimSmoothing", custom.getAimSmoothing()));
        custom.setSwitchDelayTicks(document.getInteger("switchDelayTicks", custom.getSwitchDelayTicks()));
        custom.setCritChance(readDouble(document, "critChance", custom.getCritChance()));
        custom.setBlockHitRate(readDouble(document, "blockHitRate", custom.getBlockHitRate()));
        return custom;
    }

    private static double readDouble(Document document, String key, double fallback) {
        Object value = document.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * Converts a map of unranked kit data to a MongoDB Document.
     * Each kit entry contains division, tier, wins, losses, and winstreak information.
     *
     * 将非排位套件数据映射转换为 MongoDB Document。
     * 每个套件条目包含段位、层级、胜场、败场和连胜信息。
     *
     * @param kitData A map where the key is the kit name and the value is ProfileUnrankedKitData
     *                键为套件名称、值为 ProfileUnrankedKitData 的映射
     * @return A Document representation of the unranked kit data, or empty Document if input is null
     *         非排位套件数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertUnrankedKitData(Map<String, ProfileUnrankedKitData> kitData) {
        Document kitDataDocument = new Document();
        if (kitData == null) return kitDataDocument;

        kitData.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .forEach(entry -> {
                    ProfileUnrankedKitData data = entry.getValue();
                    Document kitEntry = new DocumentBuilder()
                            .put("division", data.getDivision() != null ? data.getDivision().getName() : EMPTY_STRING)
                            .put("tier", data.getTier() != null ? data.getTier().getName() : EMPTY_STRING)
                            .put("wins", data.getWins())
                            .put("losses", data.getLosses())
                            .put("winstreak", data.getWinstreak())
                            .put("bestwinstreak", data.getBestWinstreak())
                            .put("monthlyPeriodKey", safeString(data.getMonthlyPeriodKey()))
                            .put("monthlyWins", data.getMonthlyWins())
                            .build();
                    kitDataDocument.put(entry.getKey(), kitEntry);
                });

        return kitDataDocument;
    }

    /**
     * Converts a map of ranked kit data to a MongoDB Document.
     * Each kit entry contains elo, wins, and losses information.
     *
     * 将排位套件数据映射转换为 MongoDB Document。
     * 每个套件条目包含 ELO 分数、胜场和败场信息。
     *
     * @param kitData A map where the key is the kit name and the value is ProfileRankedKitData
     *                键为套件名称、值为 ProfileRankedKitData 的映射
     * @return A Document representation of the ranked kit data, or empty Document if input is null
     *         排位套件数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertRankedKitData(Map<String, ProfileRankedKitData> kitData) {
        Document kitDataDocument = new Document();
        if (kitData == null) return kitDataDocument;

        kitData.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .forEach(entry -> {
                    ProfileRankedKitData data = entry.getValue();
                    Document kitEntry = new DocumentBuilder()
                            .put("elo", data.getElo())
                            .put("wins", data.getWins())
                            .put("losses", data.getLosses())
                            .put("winstreak", data.getWinstreak())
                            .put("bestwinstreak", data.getBestWinstreak())
                            .build();
                    kitDataDocument.put(entry.getKey(), kitEntry);
                });

        return kitDataDocument;
    }

    /**
     * Converts a map of per-kit tournament wins to a MongoDB Document.
     * 将每套件锦标赛胜场映射转换为 MongoDB Document。
     *
     * @param kitWins A map where the key is the kit name and the value is the tournament win count
     *                键为套件名称、值为锦标赛胜场数的映射
     * @return A Document representation of the kit wins, or empty Document if input is null
     *         套件胜场的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertTournamentKitWins(Map<String, Integer> kitWins) {
        Document document = new Document();
        if (kitWins == null) return document;

        kitWins.forEach(document::put);
        return document;
    }

    /**
     * Converts a map of FFA (Free For All) data to a MongoDB Document.
     * Each FFA entry contains kills, deaths, killstreak, and highest killstreak information.
     *
     * 将 FFA（自由对战）数据映射转换为 MongoDB Document。
     * 每个 FFA 条目包含击杀数、死亡数、连杀数和最高连杀数信息。
     *
     * @param ffaData A map where the key is the arena/mode name and the value is ProfileFFAData
     *                键为竞技场/模式名称、值为 ProfileFFAData 的映射
     * @return A Document representation of the FFA data, or empty Document if input is null
     *         FFA 数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertFFAData(Map<String, ProfileFFAData> ffaData) {
        Document ffaDataDocument = new Document();
        if (ffaData == null) return ffaDataDocument;

        ffaData.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .forEach(entry -> {
                    ProfileFFAData data = entry.getValue();
                    Document ffaEntry = new DocumentBuilder()
                            .put("kills", data.getKills())
                            .put("deaths", data.getDeaths())
                            .put("killstreak", data.getKillstreak())
                            .put("highestKillstreak", data.getHighestKillstreak())
                            .build();
                    ffaDataDocument.put(entry.getKey(), ffaEntry);
                });

        return ffaDataDocument;
    }

    /**
     * Converts ProfileLayoutData to a MongoDB Document.
     * Serializes layout configurations including item arrangements and display names.
     *
     * 将 ProfileLayoutData 转换为 MongoDB Document。
     * 序列化布局配置，包括物品排列和显示名称。
     *
     * @param layoutData The ProfileLayoutData object containing layout configurations
     *                   包含布局配置的 ProfileLayoutData 对象
     * @return A Document representation of the layout data, or empty Document if input is null
     *         布局数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertLayoutData(ProfileLayoutData layoutData) {
        Document layoutDocument = new Document();
        if (layoutData == null || layoutData.getLayouts() == null) return layoutDocument;

        layoutData.getLayouts().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .forEach(entry -> {
                    List<Document> layoutRecords = entry.getValue().stream()
                            .filter(Objects::nonNull)
                            .map(record -> new DocumentBuilder()
                                    .put("name", safeString(record.getName()))
                                    .put("displayName", safeString(record.getDisplayName()))
                                    .put("items", record.getItems() != null ?
                                            Serializer.serializeItemStack(record.getItems()) : EMPTY_STRING)
                                    .put("offhand", record.getOffhand() != null && record.getOffhand().getType() != Material.AIR ?
                                            Serializer.serializeItemStack(new ItemStack[]{record.getOffhand()}) : EMPTY_STRING)
                                    .build())
                            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

                    layoutDocument.put(entry.getKey(), layoutRecords);
                });

        return layoutDocument;
    }

    /**
     * Converts ProfileSettingData to a MongoDB Document.
     * Includes all user preference settings such as party messages, scoreboard visibility, etc.
     *
     * 将 ProfileSettingData 转换为 MongoDB Document。
     * 包含所有用户偏好设置，如组队消息、计分板可见性等。
     *
     * @param settingData The ProfileSettingData object containing user settings
     *                    包含用户设置的 ProfileSettingData 对象
     * @return A Document representation of the settings data, or empty Document if input is null
     *         设置数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertProfileSettingData(ProfileSettingData settingData) {
        if (settingData == null) return new Document();

        return new DocumentBuilder()
                .put("partyMessagesEnabled", settingData.isPartyMessagesEnabled())
                .put("partyInvitesEnabled", settingData.isPartyInvitesEnabled())
                .put("scoreboardEnabled", settingData.isScoreboardEnabled())
                .put("tablistEnabled", settingData.isTablistEnabled())
                .put("showScoreboardLines", settingData.isShowScoreboardLines())
                .put("profanityFilterEnabled", settingData.isProfanityFilterEnabled())
                .put("receiveDuelRequestsEnabled", settingData.isReceiveDuelRequestsEnabled())
                .put("lobbyMusicEnabled", settingData.isLobbyMusicEnabled())
                .put("serverTitles", settingData.isServerTitles())
                .put("hidePlayersEnabled", settingData.isHidePlayersEnabled())
                .put("showMatchCps", settingData.isShowMatchCps())
                .put("showMatchPing", settingData.isShowMatchPing())
                .put("showMatchOpponent", settingData.isShowMatchOpponent())
                .put("matchMvpMusicEnabled", settingData.isMatchMvpMusicEnabled())
                .put("flyOnLoss", settingData.isFlyOnLoss())
                .put("flyOnWin", settingData.isFlyOnWin())
                .put("queuePingRange", settingData.getQueuePingRange())
                .put("swingSlowlyEnabled", settingData.isSwingSlowlyEnabled())
                .put("allowSpectators", settingData.isAllowSpectators())
                .put("disablePublicChatWhenInMatch", settingData.isDisablePublicChatWhenInMatch())
                .put("hideOtherSpectators", settingData.isHideOtherSpectators())
                .put("swordBlockSoundsEnabled", settingData.isSwordBlockSoundsEnabled())
                .put("showChatLevelPrefix", settingData.isShowChatLevelPrefix())
                .put("chatChannel", safeString(settingData.getChatChannel()))
                .put("time", safeString(settingData.getTime()))
                .build();
    }

    /**
     * Converts ProfileCosmeticData to a MongoDB Document.
     * Includes selected cosmetic items such as kill effects, messages, and trails.
     *
     * 将 ProfileCosmeticData 转换为 MongoDB Document。
     * 包含选中的装饰品，如击杀特效、击杀消息和粒子轨迹。
     *
     * @param cosmeticData The ProfileCosmeticData object containing cosmetic selections
     *                     包含装饰品选择的 ProfileCosmeticData 对象
     * @return A Document representation of the cosmetic data, or empty Document if input is null
     *         装饰品数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertProfileCosmeticData(ProfileCosmeticData cosmeticData) {
        if (cosmeticData == null) return new Document();

        Document selectedCosmetics = new Document();
        for (Map.Entry<CosmeticType, String> entry : cosmeticData.getSelectedCosmetics().entrySet()) {
            selectedCosmetics.put(entry.getKey().name(), safeString(entry.getValue()));
        }

        return new DocumentBuilder()
                .put("selectedCosmetics", selectedCosmetics)
                .put("selectedKillEffect", safeString(cosmeticData.getSelectedKillEffect()))
                .put("selectedKillMessage", safeString(cosmeticData.getSelectedKillMessage()))
                .put("selectedSoundEffect", safeString(cosmeticData.getSelectedSoundEffect()))
                .put("selectedProjectileTrail", safeString(cosmeticData.getSelectedProjectileTrail()))
                .put("selectedSuit", safeString(cosmeticData.getSelectedSuit()))
                .put("selectedCloak", safeString(cosmeticData.getSelectedCloak()))
                .put("purchasedCosmetics", new java.util.ArrayList<>(cosmeticData.getPurchasedCosmetics()))
                .build();
    }

    /**
     * Converts ProfilePlayTimeData to a MongoDB Document.
     * Includes total playtime and last login information.
     *
     * 将 ProfilePlayTimeData 转换为 MongoDB Document。
     * 包含总游戏时长和最后登录信息。
     *
     * @param playTimeData The ProfilePlayTimeData object containing playtime statistics
     *                     包含游戏时长统计的 ProfilePlayTimeData 对象
     * @return A Document representation of the playtime data, or empty Document if input is null
     *         游戏时长数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertProfilePlayTimeData(ProfilePlayTimeData playTimeData) {
        if (playTimeData == null) return new Document();

        return new DocumentBuilder()
                .put("total", playTimeData.getTotal())
                .put("lastLogin", playTimeData.getLastLogin())
                .build();
    }

    /**
     * Converts ProfileMusicData to a MongoDB Document.
     * Includes the list of selected music discs.
     *
     * 将 ProfileMusicData 转换为 MongoDB Document。
     * 包含已选中的音乐唱片列表。
     *
     * @param musicData The ProfileMusicData object containing music preferences
     *                  包含音乐偏好的 ProfileMusicData 对象
     * @return A Document representation of the music data, or empty Document if input is null
     *         音乐数据的 Document 表示形式，如果输入为 null 则返回空的 Document
     */
    private static Document convertProfileMusicData(ProfileMusicData musicData) {
        if (musicData == null) return new Document();

        Set<String> selectedDiscs = musicData.getSelectedDiscs();
        return new DocumentBuilder()
                .put("selectedDiscs", selectedDiscs != null ? new ArrayList<>(selectedDiscs) : new ArrayList<>())
                .build();
    }

    private static Document convertProfileChallengeData(ProfileChallengeData challengeData) {
        return new DocumentBuilder()
                .put("dailyPeriodKey", safeString(challengeData.getDailyPeriodKey()))
                .put("weeklyPeriodKey", safeString(challengeData.getWeeklyPeriodKey()))
                .put("dailyStates", convertChallengeStates(challengeData.getDailyStates()))
                .put("weeklyStates", convertChallengeStates(challengeData.getWeeklyStates()))
                .build();
    }

    private static Document convertChallengeStates(Map<String, ProfileChallengeProgress> states) {
        Document document = new Document();
        if (states == null) return document;

        states.forEach((key, progress) -> {
            if (key == null || progress == null) return;
            document.put(key, new DocumentBuilder()
                    .put("accepted", progress.isAccepted())
                    .put("progress", progress.getProgress())
                    .put("completed", progress.isCompleted())
                    .put("rewarded", progress.isRewarded())
                    .build());
        });
        return document;
    }

    /**
     * Parses a MongoDB Document into a map of unranked kit data.
     * Reconstructs ProfileUnrankedKitData objects with division, tier, and statistics.
     * Uses DivisionService to validate and resolve division/tier references.
     *
     * 将 MongoDB Document 解析为非排位套件数据映射。
     * 使用段位、层级和统计数据重建 ProfileUnrankedKitData 对象。
     * 使用 DivisionService 验证和解析段位/层级的引用。
     *
     * @param kitDataDocument The Document containing unranked kit data
     *                        包含非排位套件数据的 Document
     * @return A map of kit names to ProfileUnrankedKitData objects, empty map if input is null
     *         套件名称到 ProfileUnrankedKitData 对象的映射，如果输入为 null 则返回空映射
     */
    private static Map<String, ProfileUnrankedKitData> parseUnrankedKitData(Document kitDataDocument) {
        Map<String, ProfileUnrankedKitData> kitData = new HashMap<>();
        if (kitDataDocument == null) return kitData;

        DivisionService divisionService = AlleyPlugin.getInstance().getService(DivisionService.class);

        kitDataDocument.forEach((key, value) -> {
            try {
                Document kitEntry = (Document) value;
                ProfileUnrankedKitData kit = new ProfileUnrankedKitData();

                String storedDivision = kitEntry.getString("division");
                if (storedDivision != null && !storedDivision.isEmpty()) {
                    Division division = divisionService.getDivision(storedDivision);
                    if (division != null) {
                        kit.setDivision(division.getName());

                        String storedTier = kitEntry.getString("tier");
                        if (storedTier != null && !storedTier.isEmpty()) {
                            division.getTiers().stream()
                                    .filter(t -> t.getName().equals(storedTier))
                                    .findFirst()
                                    .ifPresent(tier -> kit.setTier(tier.getName()));
                        }
                    }
                }

                kit.setWins(kitEntry.getInteger("wins", DEFAULT_INT));
                kit.setLosses(kitEntry.getInteger("losses", DEFAULT_INT));
                kit.setWinstreak(kitEntry.getInteger("winstreak", DEFAULT_INT));
                kit.setBestWinstreak(kitEntry.getInteger("bestwinstreak", DEFAULT_INT));
                kit.setMonthlyPeriodKey(safeString(kitEntry.getString("monthlyPeriodKey")));
                kit.setMonthlyWins(kitEntry.getInteger("monthlyWins", DEFAULT_INT));

                kitData.put(key, kit);
            } catch (Exception e) {
                Logger.logException(String.format("Failed to parse unranked kit data for key: %s", key), e);
            }
        });

        return kitData;
    }


    /**
     * Parses a MongoDB Document into a map of ranked kit data.
     * Reconstructs ProfileRankedKitData objects with elo ratings and match statistics.
     *
     * 将 MongoDB Document 解析为排位套件数据映射。
     * 使用 ELO 评分和比赛统计数据重建 ProfileRankedKitData 对象。
     *
     * @param kitDataDocument The Document containing ranked kit data
     *                        包含排位套件数据的 Document
     * @return A map of kit names to ProfileRankedKitData objects, empty map if input is null
     *         套件名称到 ProfileRankedKitData 对象的映射，如果输入为 null 则返回空映射
     */
    private static Map<String, ProfileRankedKitData> parseRankedKitData(Document kitDataDocument) {
        Map<String, ProfileRankedKitData> kitData = new HashMap<>();
        if (kitDataDocument == null) return kitData;

        kitDataDocument.forEach((key, value) -> {
            try {
                Document kitEntry = (Document) value;
                ProfileRankedKitData kit = new ProfileRankedKitData();

                kit.setElo(kitEntry.getInteger("elo", DEFAULT_ELO));
                kit.setWins(kitEntry.getInteger("wins", DEFAULT_INT));
                kit.setLosses(kitEntry.getInteger("losses", DEFAULT_INT));
                kit.setWinstreak(kitEntry.getInteger("winstreak", DEFAULT_INT));
                kit.setBestWinstreak(kitEntry.getInteger("bestwinstreak", DEFAULT_INT));

                kitData.put(key, kit);
            } catch (Exception e) {
                Logger.logException(String.format("Failed to parse ranked kit data for key: %s", key), e);
            }
        });

        return kitData;
    }

    /**
     * Parses a MongoDB Document into a map of FFA data.
     * Reconstructs ProfileFFAData objects with kill/death statistics and streaks.
     *
     * 将 MongoDB Document 解析为 FFA 数据映射。
     * 使用击杀/死亡统计和连杀记录重建 ProfileFFAData 对象。
     *
     * @param ffaDataDocument The Document containing FFA data
     *                        包含 FFA 数据的 Document
     * @return A map of arena/mode names to ProfileFFAData objects, empty map if input is null
     *         竞技场/模式名称到 ProfileFFAData 对象的映射，如果输入为 null 则返回空映射
     */
    private static Map<String, ProfileFFAData> parseFFAData(Document ffaDataDocument) {
        Map<String, ProfileFFAData> ffaData = new HashMap<>();
        if (ffaDataDocument == null) return ffaData;

        ffaDataDocument.forEach((key, value) -> {
            try {
                Document ffaEntry = (Document) value;
                ProfileFFAData ffa = new ProfileFFAData();

                ffa.setKills(ffaEntry.getInteger("kills", DEFAULT_INT));
                ffa.setDeaths(ffaEntry.getInteger("deaths", DEFAULT_INT));
                ffa.setKillstreak(ffaEntry.getInteger("killstreak", DEFAULT_INT));
                ffa.setHighestKillstreak(ffaEntry.getInteger("highestKillstreak", DEFAULT_INT));

                ffaData.put(key, ffa);
            } catch (Exception e) {
                Logger.logException(String.format("Failed to parse FFA data for key: %s", key), e);
            }
        });

        return ffaData;
    }

    /**
     * Parses a MongoDB Document into ProfileLayoutData.
     * Deserializes layout configurations and reconstructs ItemStack arrays from serialized strings.
     * Handles deserialization errors gracefully by logging and using empty ItemStack arrays.
     *
     * 将 MongoDB Document 解析为 ProfileLayoutData。
     * 反序列化布局配置，并从序列化字符串重建 ItemStack 数组。
     * 通过记录日志和使用空 ItemStack 数组来优雅地处理反序列化错误。
     *
     * @param layoutDocument The Document containing layout data
     *                       包含布局数据的 Document
     * @return A ProfileLayoutData object with reconstructed layouts, or new instance if input is null
     *         包含重建布局的 ProfileLayoutData 对象，如果输入为 null 则返回新实例
     */
    private static ProfileLayoutData parseProfileLayoutData(Document layoutDocument) {
        ProfileLayoutData layoutData = new ProfileLayoutData();
        if (layoutDocument == null) return layoutData;

        layoutDocument.forEach((key, value) -> {
            try {
                List<LayoutData> layoutRecords = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Document> records = (List<Document>) value;

                if (records != null) {
                    records.stream()
                            .filter(Objects::nonNull)
                            .forEach(record -> {
                                String name = record.getString("name");
                                String displayName = record.getString("displayName");
                                String itemsString = record.getString("items");

                                ItemStack[] items = null;
                                if (itemsString != null && !itemsString.isEmpty()) {
                                    try {
                                        items = Serializer.deserializeItemStack(itemsString);
                                    } catch (Exception e) {
                                        Logger.logException(String.format("Failed to deserialize items for layout: %s", name), e);
                                    }
                                }

                                LayoutData layoutRecord = new LayoutData(
                                        safeString(name),
                                        safeString(displayName),
                                        items != null ? items : new ItemStack[0]
                                );

                                String offhandString = record.getString("offhand");
                                if (offhandString != null && !offhandString.isEmpty()) {
                                    try {
                                        ItemStack[] offhandArr = Serializer.deserializeItemStack(offhandString);
                                        if (offhandArr != null && offhandArr.length > 0) {
                                            layoutRecord.setOffhand(offhandArr[0]);
                                        }
                                    } catch (Exception e) {
                                        Logger.logException(String.format("Failed to deserialize offhand for layout: %s", name), e);
                                    }
                                }

                                layoutRecords.add(layoutRecord);
                            });
                }
                layoutData.getLayouts().put(key, layoutRecords);
            } catch (Exception e) {
                Logger.logException(String.format("Failed to parse layout data for key: %s", key), e);
            }
        });

        return layoutData;
    }

    /**
     * Parses a MongoDB Document into ProfileMusicData.
     * Reconstructs the set of selected music discs from the stored list.
     * Falls back to default music data if parsing fails.
     *
     * 将 MongoDB Document 解析为 ProfileMusicData。
     * 从存储的列表中重建已选中的音乐唱片集合。
     * 如果解析失败，则回退到默认音乐数据。
     *
     * @param musicDocument The Document containing music data
     *                      包含音乐数据的 Document
     * @return A ProfileMusicData object with selected discs, or default music data if parsing fails
     *         包含已选中唱片的 ProfileMusicData 对象，如果解析失败则返回默认音乐数据
     */
    private static ProfileMusicData parseProfileMusicData(Document musicDocument) {
        if (musicDocument == null) {
            return createDefaultMusicData();
        }

        try {
            ProfileMusicData musicData = new ProfileMusicData();
            List<String> selectedDiscs = musicDocument.getList("selectedDiscs", String.class);

            if (selectedDiscs != null && !selectedDiscs.isEmpty()) {
                musicData.getSelectedDiscs().addAll(selectedDiscs);
                return musicData;
            }
        } catch (Exception e) {
            Logger.logException("Failed to parse music data, using defaults", e);
        }

        return createDefaultMusicData();
    }

    private static ProfileChallengeData parseProfileChallengeData(Document challengeDocument) {
        ProfileChallengeData challengeData = new ProfileChallengeData();
        if (challengeDocument == null) return challengeData;

        challengeData.setDailyPeriodKey(challengeDocument.getString("dailyPeriodKey") == null
                ? EMPTY_STRING : challengeDocument.getString("dailyPeriodKey"));
        challengeData.setWeeklyPeriodKey(challengeDocument.getString("weeklyPeriodKey") == null
                ? EMPTY_STRING : challengeDocument.getString("weeklyPeriodKey"));
        challengeData.setDailyStates(parseChallengeStates(challengeDocument.get("dailyStates", Document.class)));
        challengeData.setWeeklyStates(parseChallengeStates(challengeDocument.get("weeklyStates", Document.class)));
        return challengeData;
    }

    private static Map<String, ProfileChallengeProgress> parseChallengeStates(Document statesDocument) {
        Map<String, ProfileChallengeProgress> states = new HashMap<>();
        if (statesDocument == null) return states;

        statesDocument.forEach((key, value) -> {
            if (!(value instanceof Document progressDocument)) return;
            ProfileChallengeProgress progress = new ProfileChallengeProgress();
            progress.setAccepted(progressDocument.getBoolean("accepted", DEFAULT_BOOLEAN_FALSE));
            progress.setProgress(Math.max(DEFAULT_INT, progressDocument.getInteger("progress", DEFAULT_INT)));
            progress.setCompleted(progressDocument.getBoolean("completed", DEFAULT_BOOLEAN_FALSE));
            progress.setRewarded(progressDocument.getBoolean("rewarded", DEFAULT_BOOLEAN_FALSE));
            states.put(key, progress);
        });
        return states;
    }

    /**
     * Parses a MongoDB Document into ProfileSettingData.
     * Reconstructs all user preference settings with appropriate default values.
     * Validates enum values for ChatChannel and WorldTime settings.
     *
     * 将 MongoDB Document 解析为 ProfileSettingData。
     * 使用适当的默认值重建所有用户偏好设置。
     * 验证 ChatChannel 和 WorldTime 设置的枚举值。
     *
     * @param settingDocument The Document containing settings data
     *                        包含设置数据的 Document
     * @return A ProfileSettingData object with user preferences, or new instance with defaults if input is null
     *         包含用户偏好的 ProfileSettingData 对象，如果输入为 null 则返回包含默认值的新实例
     */
    private static ProfileSettingData parseProfileSettingData(Document settingDocument) {
        ProfileSettingData settingData = new ProfileSettingData();
        if (settingDocument == null) return settingData;

        settingData.setPartyMessagesEnabled(settingDocument.getBoolean("partyMessagesEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setPartyInvitesEnabled(settingDocument.getBoolean("partyInvitesEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setScoreboardEnabled(settingDocument.getBoolean("scoreboardEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setTablistEnabled(settingDocument.getBoolean("tablistEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setShowScoreboardLines(settingDocument.getBoolean("showScoreboardLines", DEFAULT_BOOLEAN_TRUE));
        settingData.setProfanityFilterEnabled(settingDocument.getBoolean("profanityFilterEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setReceiveDuelRequestsEnabled(settingDocument.getBoolean("receiveDuelRequestsEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setLobbyMusicEnabled(settingDocument.getBoolean("lobbyMusicEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setServerTitles(settingDocument.getBoolean("serverTitles", DEFAULT_BOOLEAN_TRUE));
        settingData.setHidePlayersEnabled(settingDocument.getBoolean("hidePlayersEnabled", DEFAULT_BOOLEAN_FALSE));
        settingData.setShowMatchCps(settingDocument.getBoolean("showMatchCps", DEFAULT_BOOLEAN_FALSE));
        settingData.setShowMatchPing(settingDocument.getBoolean("showMatchPing", DEFAULT_BOOLEAN_TRUE));
        settingData.setShowMatchOpponent(settingDocument.getBoolean("showMatchOpponent", DEFAULT_BOOLEAN_TRUE));
        settingData.setMatchMvpMusicEnabled(settingDocument.getBoolean("matchMvpMusicEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setFlyOnLoss(settingDocument.getBoolean("flyOnLoss", DEFAULT_BOOLEAN_FALSE));
        settingData.setFlyOnWin(settingDocument.getBoolean("flyOnWin", DEFAULT_BOOLEAN_TRUE));
        settingData.setQueuePingRange(settingDocument.getInteger("queuePingRange", DEFAULT_INT));
        settingData.setSwingSlowlyEnabled(settingDocument.getBoolean("swingSlowlyEnabled", DEFAULT_BOOLEAN_TRUE));
        settingData.setAllowSpectators(settingDocument.getBoolean("allowSpectators", DEFAULT_BOOLEAN_TRUE));
        settingData.setDisablePublicChatWhenInMatch(settingDocument.getBoolean("disablePublicChatWhenInMatch", DEFAULT_BOOLEAN_FALSE));
        settingData.setHideOtherSpectators(settingDocument.getBoolean("hideOtherSpectators", DEFAULT_BOOLEAN_FALSE));
        settingData.setSwordBlockSoundsEnabled(settingDocument.getBoolean("swordBlockSoundsEnabled", DEFAULT_BOOLEAN_FALSE));
        settingData.setShowChatLevelPrefix(settingDocument.getBoolean("showChatLevelPrefix", DEFAULT_BOOLEAN_FALSE));

        String chatChannel = settingDocument.getString("chatChannel");
        settingData.setChatChannel(chatChannel != null ? chatChannel : ChatChannel.GLOBAL.toString());

        String time = settingDocument.getString("time");
        settingData.setTime(time != null ? time : WorldTime.DEFAULT.getName());

        return settingData;
    }


    /**
     * Parses a MongoDB Document into ProfileCosmeticData.
     * Reconstructs selected cosmetic items from stored string identifiers.
     *
     * 将 MongoDB Document 解析为 ProfileCosmeticData。
     * 从存储的字符串标识符中重建已选中的装饰品。
     *
     * @param cosmeticDocument The Document containing cosmetic data
     *                         包含装饰品数据的 Document
     * @return A ProfileCosmeticData object with cosmetic selections, or new instance if input is null
     *         包含装饰品选择的 ProfileCosmeticData 对象，如果输入为 null 则返回新实例
     */
    private static ProfileCosmeticData parseProfileCosmeticData(Document cosmeticDocument) {
        ProfileCosmeticData cosmeticData = new ProfileCosmeticData();
        if (cosmeticDocument == null) return cosmeticData;

        Document selectedCosmetics = cosmeticDocument.get("selectedCosmetics", Document.class);
        if (selectedCosmetics != null) {
            for (CosmeticType type : CosmeticType.values()) {
                String value = selectedCosmetics.getString(type.name());
                if (value != null) {
                    cosmeticData.getSelectedCosmetics().put(type, value);
                }
            }
            java.util.List<String> p2 = cosmeticDocument.getList("purchasedCosmetics", String.class);
            if (p2 != null) cosmeticData.setPurchasedCosmetics(new java.util.HashSet<>(p2));
            return cosmeticData;
        }

        for (CosmeticType type : CosmeticType.values()) {
            String legacyFieldName = getLegacyFieldName(type);
            String value = cosmeticDocument.getString(legacyFieldName);
            if (value != null) {
                cosmeticData.getSelectedCosmetics().put(type, value);
            }
        }

        java.util.List<String> purchased = cosmeticDocument.getList("purchasedCosmetics", String.class);
        if (purchased != null) {
            cosmeticData.setPurchasedCosmetics(new java.util.HashSet<>(purchased));
        }

        return cosmeticData;
    }

    /**
     * Parses a MongoDB Document into ProfilePlayTimeData.
     * Reconstructs playtime statistics with null-safe handling for Long values.
     *
     * 将 MongoDB Document 解析为 ProfilePlayTimeData。
     * 使用空安全处理重建游戏时长统计数据。
     *
     * @param playTimeDocument The Document containing playtime data
     *                         包含游戏时长数据的 Document
     * @return A ProfilePlayTimeData object with playtime statistics, or new instance with defaults if input is null
     *         包含游戏时长统计的 ProfilePlayTimeData 对象，如果输入为 null 则返回包含默认值的新实例
     */
    private static ProfilePlayTimeData parseProfilePlayTimeData(Document playTimeDocument) {
        ProfilePlayTimeData playTimeData = new ProfilePlayTimeData();
        if (playTimeDocument == null) return playTimeData;

        Long total = playTimeDocument.getLong("total");
        Long lastLogin = playTimeDocument.getLong("lastLogin");

        playTimeData.setTotal(total != null ? total : DEFAULT_LONG);
        playTimeData.setLastLogin(lastLogin != null ? lastLogin : DEFAULT_LONG);

        return playTimeData;
    }

    /**
     * Returns the legacy field name for a given CosmeticType.
     * This method is used to maintain backward compatibility with older database schemas.
     *
     * 返回给定 CosmeticType 的旧版字段名称。
     * 此方法用于保持与旧版数据库模式的向后兼容性。
     *
     * @param type The CosmeticType to get the legacy field name for
     *             要获取旧版字段名称的 CosmeticType
     * @return The legacy field name as a String
     *         旧版字段名称字符串
     */
    private static String getLegacyFieldName(CosmeticType type) {
        switch (type) {
            case KILL_EFFECT: return "selectedKillEffect";
            case KILL_MESSAGE: return "selectedKillMessage";
            case SOUND_EFFECT: return "selectedSoundEffect";
            case PROJECTILE_TRAIL: return "selectedProjectileTrail";
            case SUIT: return "selectedSuit";
            case CLOAK: return "selectedCloak";
            default: return "selected" + type.name();
        }
    }

    /**
     * Safe string handling that prevents null pointer exceptions.
     * Provides a consistent way to handle potentially null string values throughout the utility.
     *
     * 防止空指针异常的安全字符串处理。
     * 在整个工具类中提供统一的方式来处理可能为 null 的字符串值。
     *
     * @param value The string value to check
     *              要检查的字符串值
     * @return The original string if not null, otherwise an empty string
     *         如果不为 null 则返回原始字符串，否则返回空字符串
     */
    private static String safeString(String value) {
        return value != null ? value : EMPTY_STRING;
    }

    /**
     * Safe list handling that prevents null pointer exceptions.
     * Creates a defensive copy of the input list to prevent external modifications.
     *
     * 防止空指针异常的安全列表处理。
     * 创建输入列表的防御性副本以防止外部修改。
     *
     * @param list The list to check and copy
     *             要检查并复制的列表
     * @param <T> The type of elements in the list
     *            列表中元素的类型
     * @return A new ArrayList containing the elements of the input list, or an empty list if input is null
     *         包含输入列表元素的新 ArrayList，如果输入为 null 则返回空列表
     */
    private static <T> List<T> safeList(List<T> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * Generic method for parsing and merging map data from a Document.
     * Provides a standardized way to handle map-based data fields with error recovery.
     * If parsing fails, the existing map is preserved to maintain data integrity.
     *
     * 用于从 Document 解析并合并映射数据的泛型方法。
     * 提供了一种标准化的方式来处理基于映射的数据字段，并包含错误恢复机制。
     * 如果解析失败，将保留现有映射以维护数据完整性。
     *
     * @param document The Document to parse from
     *                 要从中解析的 Document
     * @param key The key to look for in the Document
     *            要从 Document 中查找的键
     * @param parser The function to parse the sub-document into a map
     *               将子文档解析为映射的函数
     * @param existingMap The existing map to merge parsed data into
     *                    要合并解析数据的现有映射
     * @param setter The consumer to set the final merged map back to the parent object
     *               设置最终合并后的映射到父对象的消费者
     * @param <T> The type of values in the map
     *            映射中值的类型
     */
    private static <T> void parseAndMerge(Document document, String key,
                                          Function<Document, Map<String, T>> parser,
                                          Map<String, T> existingMap,
                                          Consumer<Map<String, T>> setter) {
        try {
            Document subDocument = document.get(key, Document.class);
            if (subDocument != null) {
                Map<String, T> parsedData = parser.apply(subDocument);
                if (parsedData != null && !parsedData.isEmpty()) {
                    existingMap.putAll(parsedData);
                    setter.accept(existingMap);
                    return;
                }
            }
        } catch (Exception e) {
            Logger.logException(String.format("Failed to parse and merge field: %s", key), e);
        }

        setter.accept(existingMap);
    }

    /**
     * Generic method for parsing a sub-document and setting it to a field.
     * Provides a standardized way to handle complex object fields with fallback to defaults.
     * Ensures robust error handling and prevents null values from being set.
     *
     * 用于解析子文档并将其设置到字段的泛型方法。
     * 提供了一种标准化的方式来处理复杂对象字段，并具有回退到默认值的机制。
     * 确保健壮的错误处理，并防止设置 null 值。
     *
     * @param document The Document to parse from
     *                 要从中解析的 Document
     * @param key The key to look for in the Document
     *            要从 Document 中查找的键
     * @param parser The function to parse the sub-document into the target type
     *               将子文档解析为目标类型的函数
     * @param setter The consumer to set the parsed value to the parent object
     *               设置解析后的值到父对象的消费者
     * @param defaultSupplier A supplier to create a default value if parsing fails or document is null
     *                        当解析失败或文档为 null 时创建默认值的供应者
     * @param <T> The type of the parsed value
     *            解析值的类型
     */
    private static <T> void parseAndSet(Document document, String key,
                                        Function<Document, T> parser,
                                        Consumer<T> setter,
                                        Supplier<T> defaultSupplier) {
        try {
            Document subDocument = document.get(key, Document.class);
            if (subDocument != null) {
                T parsed = parser.apply(subDocument);
                if (parsed != null) {
                    setter.accept(parsed);
                    return;
                }
            }
        } catch (Exception e) {
            Logger.logException(String.format("Failed to parse and set field: %s", key), e);
        }

        try {
            setter.accept(defaultSupplier.get());
        } catch (Exception e) {
            Logger.logException(String.format("Failed to create default value for field: %s", key), e);
        }
    }

    /**
     * Creates default music data with all available music discs selected.
     * This method is used when music data is not present in the database document,
     * ensuring users have access to all available music by default.
     * Handles service lookup failures gracefully by returning empty music data.
     *
     * 创建包含所有可用音乐唱片的默认音乐数据。
     * 当数据库文档中没有音乐数据时，使用此方法来确保用户默认可以访问所有可用音乐。
     * 通过返回空音乐数据来优雅地处理服务查找失败的情况。
     *
     * @return A ProfileMusicData object with all available music discs, or empty music data if service unavailable
     *         包含所有可用音乐唱片的 ProfileMusicData 对象，如果服务不可用则返回空音乐数据
     */
    private static ProfileMusicData createDefaultMusicData() {
        ProfileMusicData musicData = new ProfileMusicData();
        try {
            MusicService musicService = AlleyPlugin.getInstance().getService(MusicService.class);
            if (musicService != null && musicService.getMusicDiscs() != null) {
                musicService.getMusicDiscs().forEach(disc -> {
                    if (disc != null) {
                        musicData.addDisc(disc.name());
                    }
                });
            }
        } catch (Exception e) {
            Logger.logException("Failed to create default music data", e);
        }
        return musicData;
    }

    /**
     * A builder class for constructing MongoDB Documents with null safety and error handling.
     * This class allows for chaining method calls to build a Document incrementally.
     *
     * 用于构建 MongoDB Document 的构建器类，包含空安全和错误处理。
     * 此类允许通过链式方法调用逐步构建 Document。
     */
    private static class DocumentBuilder {
        private final Document document;

        public DocumentBuilder() {
            this.document = new Document();
        }

        public DocumentBuilder put(String key, Object value) {
            if (key != null) {
                document.put(key, value);
            }
            return this;
        }

        public <T> DocumentBuilder putSafe(String key, Supplier<T> supplier, Function<T, Document> converter) {
            try {
                T value = supplier.get();
                if (value != null) {
                    Document converted = converter.apply(value);
                    if (converted != null) {
                        document.put(key, converted);
                    }
                }
            } catch (Exception e) {
                Logger.logException(String.format("Failed to put safe value for key: %s", key), e);
            }
            return this;
        }

        public Document build() {
            return document;
        }
    }
}
