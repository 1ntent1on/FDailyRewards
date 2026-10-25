package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.Storage.Database;
import com.fretka46.fDailyRewards.Storage.Database.Source;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Jediná vstupní brána pro stav a zápis claimů. Žádný jiný kód nesmí odvozovat stav z databáze zvlášť.
 * Zápisové metody jen zapisují do DB; spouštění příkazů odměn zůstává na volajícím (potřebuje server).
 */
public final class RewardService {
    private record Cached<T>(Instant at, LocalDate date, T value) {}

    /** Výsledek {@code giftnext}: {@code day > 0} = zapsáno, jinak {@code reason} říká proč ne. */
    public record GiftResult(int day, Reason reason) {
        public boolean ok() {
            return day > 0;
        }
    }

    private final Database db;
    private final TimeService time;
    private final Supplier<RewardCatalog> catalog;
    private final PassService pass;
    private final IntSupplier cacheMillis;
    private final Consumer<YearMonth> monthSeen;
    private final Map<UUID, Cached<ClaimState>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<PassService.Info>> passCache = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<Boolean>> loginCache = new ConcurrentHashMap<>();

    /**
     * @param monthSeen zavolá se s měsícem odměn před každým vyhodnocením (plugin podle něj přepne soubor odměn
     *                  po přechodu měsíce); {@code m -> {}} = nic
     */
    public RewardService(Database db, TimeService time, Supplier<RewardCatalog> catalog, PassService pass, IntSupplier cacheMillis,
                         Consumer<YearMonth> monthSeen) {
        this.db = db;
        this.time = time;
        this.catalog = catalog;
        this.pass = pass;
        this.cacheMillis = cacheMillis;
        this.monthSeen = monthSeen;
    }

    public TimeService time() {
        return time;
    }

    public PassService pass() {
        return pass;
    }

    public RewardCatalog catalog() {
        return catalog.get();
    }

    /** Hodnota s krátkou cache; platí jen ve stejném dni odměn. */
    private <T> T cached(Map<UUID, Cached<T>> map, UUID uuid, Function<UUID, T> load) {
        Instant now = time.now();
        LocalDate date = time.rewardDate(now);
        Cached<T> c = map.get(uuid);
        if (c != null && c.date.equals(date) && now.toEpochMilli() - c.at.toEpochMilli() < cacheMillis.getAsInt()) return c.value;
        T v = load.apply(uuid);
        map.put(uuid, new Cached<>(now, date, v));
        return v;
    }

    /** Stav s krátkou cache (pro PlaceholderAPI, které se volá často). */
    public ClaimState evaluate(UUID uuid) {
        return cached(cache, uuid, this::evaluateFresh);
    }

    /** Stav bez cache: vždy pro rozhodnutí o zápisu (klik, giftnext, unlock). */
    public ClaimState evaluateFresh(UUID uuid) {
        Instant now = time.now();
        YearMonth month = time.month(now);
        monthSeen.accept(month);
        var input = new RewardEngine.Input(db.claimedDays(uuid, month), db.lastCooldownClaim(uuid), pass.isActive(uuid));
        return RewardEngine.evaluate(catalog.get(), time, now, input);
    }

    /** Login Pass s krátkou cache (placeholdery {@code vip_*}). */
    public PassService.Info passInfo(UUID uuid) {
        return cached(passCache, uuid, pass::info);
    }

    /** Zda hráč dnes dostal Premium Pass bonus za login, s krátkou cache. */
    public boolean loginBonusToday(UUID uuid) {
        return cached(loginCache, uuid, u -> db.hasLoggedIn(u, time.today()));
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
        passCache.remove(uuid);
        loginCache.remove(uuid);
    }

    public void invalidateAll() {
        cache.clear();
        passCache.clear();
        loginCache.clear();
    }

    /** Běžné vyzvednutí hráčem. Při {@link Reason#OK} je den už zapsaný (zdroj claim). */
    public Reason claim(UUID uuid, int day) {
        ClaimState s = evaluateFresh(uuid);
        Reason r = s.reasonForDay(day, catalog.get());
        if (r != Reason.OK) return r;
        if (!db.tryInsertClaim(uuid, YearMonth.from(s.rewardDate()), s.rewardDate(), day, Source.CLAIM)) return Reason.ALREADY_CLAIMED;
        invalidate(uuid);
        return Reason.OK;
    }

    /**
     * Další den k vyzvednutí podle {@link #evaluateFresh}, bez cooldownu. V režimu {@link GiftMode#CATCHUP} jen den,
     * který už kalendářně nastal (zameškaný); {@link GiftMode#ANY} dá i den dopředu.
     */
    public GiftResult reserveNextGift(UUID uuid, GiftMode mode) {
        ClaimState s = evaluateFresh(uuid);
        if (s.nextDay() == 0) return new GiftResult(0, s.reason());
        if (mode == GiftMode.CATCHUP && s.nextDay() > s.rewardDate().getDayOfMonth()) return new GiftResult(0, Reason.NOT_YET);
        if (!db.tryInsertClaim(uuid, YearMonth.from(s.rewardDate()), s.rewardDate(), s.nextDay(), Source.GIFT))
            return new GiftResult(0, Reason.ALREADY_CLAIMED);
        invalidate(uuid);
        return new GiftResult(s.nextDay(), Reason.OK);
    }

    /** Konkrétní den jako dárek. {@link Reason#OK} = zapsáno. */
    public Reason reserveGiftDay(UUID uuid, int day) {
        ClaimState s = evaluateFresh(uuid);
        RewardCatalog cat = catalog.get();
        if (!cat.has(day)) return Reason.UNKNOWN_DAY;
        if (s.claimedDays().contains(day)) return Reason.ALREADY_CLAIMED;
        if (cat.isVip(day) && !s.pass()) return Reason.VIP_ONLY;
        if (!db.tryInsertClaim(uuid, YearMonth.from(s.rewardDate()), s.rewardDate(), day, Source.GIFT)) return Reason.ALREADY_CLAIMED;
        invalidate(uuid);
        return Reason.OK;
    }

    /** Označí den jako vyzvednutý bez dopadu na cooldown (oprava DB). */
    public Reason markDay(UUID uuid, int day) {
        ClaimState s = evaluateFresh(uuid);
        if (!catalog.get().has(day)) return Reason.UNKNOWN_DAY;
        if (!db.tryInsertClaim(uuid, YearMonth.from(s.rewardDate()), s.rewardDate(), day, Source.UNLOCK)) return Reason.ALREADY_CLAIMED;
        invalidate(uuid);
        return Reason.OK;
    }

    /** Zapíše (zdroj unlock) a vrátí dny zameškané do dneška, které hráč smí; nejvýš {@code max} dní. */
    public List<Integer> unlockMissed(UUID uuid, int max) {
        ClaimState s = evaluateFresh(uuid);
        List<Integer> missed = RewardEngine.missedDays(catalog.get(), s.rewardDate(), s.claimedDays(), s.pass());
        List<Integer> done = new ArrayList<>();
        for (int d : missed) {
            if (done.size() >= max) break;
            if (db.tryInsertClaim(uuid, YearMonth.from(s.rewardDate()), s.rewardDate(), d, Source.UNLOCK)) done.add(d);
        }
        invalidate(uuid);
        return done;
    }

    public int resetDay(UUID uuid, int day) {
        int n = db.deleteClaim(uuid, time.month(time.now()), day);
        invalidate(uuid);
        return n;
    }
}
