# FDailyRewards 1.1.1

Update pluginu denních odměn pro TheCivia (základ: zdroj od fretka46, viz `../FDailyRewards-original-src`).
Zadání: `../zadani-2-fdailyrewards.md`. Paper 26.3, Java 25.

## Co je nového

| Oblast | Změna |
|---|---|
| Stav odměn | Jedno místo, které rozhoduje (`Core/RewardEngine` přes `RewardService.evaluate`). Menu, klik, připomínky i PlaceholderAPI čtou jen odtud, takže ikonka vždy odpovídá tomu, co klik udělá. |
| Chyba ikonky | Menu už nepřepisuje sdílené objekty z configu (po otevření jedním hráčem svítil den „dostupný“ všem). Config je po načtení neměnný, menu pracuje s kopiemi. |
| Den odměn | Všude jediný pojem `rewardDate` (reset `day_reset_time`, zóna `Europe/Prague`). 00:00–04:14 patří ještě předchozímu dni, měsíc i dny se odvozují od něj. |
| Login Pass | Po kalendářních měsících, tabulka `vip_players`. Každý nákup přidá měsíc **za posledním zaplaceným**. Pass končí 1. dne dalšího měsíce v 04:15. |
| Nové příkazy | `givepass`, `removepass`, `passinfo`, `giftnext`, `unlockmissed … [give|mark]`. Fungují i pro offline hráče. |
| Placeholdery | 19 nových `%fdr_…%` (tabulka níže), cache 5 s. |
| Připomínky | Jedna smyčka místo úlohy na hráče: po připojení za 8 s, po resetu dne s rozptylem 0–60 s, jinak po `reward_claim_available_interval`. Jen dokud je odměna opravdu dostupná. Čas poslední připomínky je v DB (restart serveru nevyvolá zprávu navíc). |
| Bezpečnost dat | Atomický zápis claimu (`UNIQUE(uuid, měsíc, den)`, dvojklik nevydá odměnu dvakrát), atomický login bonus, jedno spojení se zámkem, schéma s verzí, **automatická záloha DB před migrací**. |
| Přechod z permission | `legacy_pass_permission` (viz nasazení): Pass z LuckPerms se do vypnutí dál uznává. Nikdo nepřijde o Pass při nasazení JARu před úpravou Tebexu. |

## Změny v 1.1.1 (opravy po review, `../zadani-2b-fdailyrewards-opravy.md`)

| Oblast | Změna |
|---|---|
| Ikonka „vyzvednuto“ | Znovu celý item ze šablony `reward_claimed_item(_vip)` (lore „už máš vyzvednutou“, tooltip), z odměny jen název. V 1.1.0 se omylem brala lore odměny. |
| `giftnext` | Nástroj pro AT na požádání hráče, **ne Tebex balíček**. Nový klíč `giftnext_mode` (výchozí `catchup` = jen zameškaný den). V 1.1.0 dal hráči, který nic nezameškal, zítřejší den dnes, a zítra pak neměl co vyzvednout. |
| Neúspěšné nákupy | `givepass`, `setvip` z konzole (Login Pass z Tebexu): každý neúspěch jde do `failed-purchases.log` (+ volitelně `failed_purchase_command`). Tebex nabije i při neúspěchu. |
| Přechod měsíce | Soubor `MM_rewards.yml` se přepne při prvním vyhodnocení po resetu, už nezávisí na zapnutých připomínkách. |
| Čekání po VIP dni | Den navíc jen tehdy, když hráč VIP den opravdu přeskočil (ne když ho vyzvedl s Passem, který pak vypršel). `next_in` u čekání na kalendář počítá i tento den navíc. |
| Fronta dárků | Nemaže se po 90 dnech (hráč by přišel o dárek od AT). Nový příkaz `admin pending`. Rychlý relog už nemůže doručit dárek dvakrát. |
| Hromadné předání | `unlockmissed give` a víc dárků z fronty: 1 den za tick, bez příkazů z `bulk_skip_command_prefixes` (Discord, ctext, bossbar), jedna souhrnná zpráva. |
| Placeholdery | `vip_*` a `login_bonus_today` přes cache. Texty `placeholders.yes_text`/`no_text` (klíče `yes`/`no` YAML čte jako true/false, takže nešly změnit). |
| Login Pass | Konec platnosti a `vip_months_left` podle souvislé řady zaplacených měsíců (mezera po `removepass X YYYYMM` už konec nenafukuje); měsíce za mezerou vypíše `passinfo`. |
| Příkazy | Hráč musí být serveru znám (`hasPlayedBefore`), i při zadání UUID. Admin podpříkazy vidí v tab-complete jen ten, kdo na ně má právo. |

## Příkazy

Vše pod `/fdailyrewards` (alias `/daily` řeší CMI CustomAlias a zůstává). Konzole má právo vždy.

| Příkaz | Právo | Funkce |
|---|---|---|
| `admin givepass <hráč> [měsíce=1]` | `fdailyrewards.admin.givepass` | Přidá měsíce za poslední zaplacený (nebo od aktuálního). |
| `admin removepass <hráč> [YYYYMM]` | `…removepass` | Odebere měsíc, bez argumentu poslední. |
| `admin passinfo <hráč>` | `…passinfo` | Zaplacené měsíce a konec platnosti. |
| `admin setvip <hráč>` / `removevip <hráč> <1-12>` | `…setvip` | Zpětně kompatibilní (`setvip` = `givepass 1`). |
| `admin giftnext <hráč>` | `…giftnext` | **Nástroj pro AT na požádání hráče** (není v obchodě). Dá další den, který hráč smí (VIP přeskočí bez Passu), mimo cooldown, zdroj `gift`. Režim `giftnext_mode`: `catchup` (výchozí) dá jen zameškaný den, `any` i den dopředu. Když není co dát, nic nepředá a vypíše proč. Offline hráč: fronta `pending_gifts`, doručí se při připojení. |
| `admin giftday <hráč> <den>` | `…giftday` | Nástroj pro AT: konkrétní den. Hlídá duplicitu a VIP, zdroj `gift`. |
| `admin unlockmissed <hráč> [give\|mark]` | `…unlockmissed` | Zameškané dny do dneška (VIP jen s Passem). `give` předá odměny (víc dní hromadně, viz `bulk_skip_command_prefixes`), `mark` jen označí. Zdroj `unlock` (nezablokuje dnešní claim), max `unlock_max_days`. |
| `admin pending <hráč> [clear]` | `…pending` | Čekající dárky offline hráče (id, den, datum). `clear` je smaže (zapíše do logu, co smazal). |
| `admin setday <hráč> <den>` | `…setday` | Označí den jako vyzvednutý bez odměny (cooldown se nemění). |
| `admin resetday <hráč> <den>` | `…resetday` | Smaže claim dne **jen v aktuálním měsíci** (dřív mazal všechny měsíce). |
| `admin checkday <hráč>` | `…checkday` | Stav hráče. |
| `reload` | `fdailyrewards.reload` | Znovu načte config, odměny a služby. |

### Pro AT: dárky na požádání

Oprávnění se přidávají přes LuckPerms (výchozí `op`), hráči je nemají. Pro AT stačí:
`fdailyrewards.admin.giftnext`, `fdailyrewards.admin.giftday`, `fdailyrewards.admin.unlockmissed`, `fdailyrewards.admin.checkday`, `fdailyrewards.admin.pending`.

| Situace | Příkaz |
|---|---|
| Hráč zameškal den (nemohl hrát, výpadek serveru) a žádá o něj | `/fdailyrewards admin giftnext <hráč>`: dá nejstarší zameškaný den, dnešní běžné vyzvednutí mu zůstane |
| Zameškal víc dní (např. delší výpadek) | `/fdailyrewards admin unlockmissed <hráč> give`: všechny zameškané dny do dneška najednou (bez zpráv na Discord za každý den) |
| Konkrétní den (oprava chyby) | `/fdailyrewards admin giftday <hráč> <den>` |
| Nejdřív zjistit stav | `/fdailyrewards admin checkday <hráč>`: další den, kolik zameškal, zda má Pass |
| Hráč je offline | dárek čeká ve frontě a doručí se při připojení; `/fdailyrewards admin pending <hráč>` ho ukáže |

`giftnext` v režimu `catchup` odmítne hráče, který nic nezameškal („hráč nemá zameškaný den“), aby AT omylem nedal zítřejší odměnu dopředu (hráč by ji pak zítra neměl). Bez Login Passu se VIP dny přeskakují.

## Placeholdery `%fdr_…%`

Offline hráč vrací prázdný řetězec. Texty jsou v configu: `placeholders.yes_text`, `no_text`, `dash`, `less_than_minute`.

| Placeholder | Hodnota |
|---|---|
| `current_day` | den, který hráč právě může/bude vyzvedávat, `0` když je vše vyzvednuto |
| `claimed_day` | poslední vyzvednutý den (beze změny) |
| `claimed_count`, `progress` | počet vyzvednutých / `12/31` |
| `calendar_day`, `days_in_month` | den v měsíci podle dne odměn / počet dní odměn dostupných tento měsíc |
| `can_claim`, `claimed_today` | `yes`/`no` |
| `state` | `ready` / `claimed_today` / `waiting` / `vip_locked` / `done` |
| `next_in` | `3 h 12 min` do dalšího vyzvednutí, `–` když je dostupné |
| `next_reward_name` | název další odměny bez MiniMessage |
| `missed_count` | kolik dní by odemkl `unlockmissed` |
| `vip_active` | `yes`/`no` (zahrnuje přechodovou permission) |
| `vip_months_left`, `vip_expires`, `vip_left` | souvislá řada zaplacených měsíců vč. aktuálního / `30. 11. 2026` / `12 dní 4 h` |
| `vip_expires_unix` | epoch sekundy konce platnosti, `0` když není (pro CiviaRank; permission se tu nepočítá) |
| `login_bonus_today` | zda hráč dnes dostal Premium Pass bonus |

## Pravidla odměn (zachovaná z původního pluginu)

- Další den = nejnižší nevyzvednutý; bez Passu se VIP dny přeskakují.
- Běžný den jednou za den odměn. Bez Passu se po přeskočeném VIP dni čeká o den déle (menu: „za 2 dny“). Když hráč VIP den vyzvedl (s Passem, který mezitím vypršel), den navíc se nepřidává.
- VIP den s Passem jde vyzvednout kdykoliv, ale zabere dnešní claim (další běžný den až zítra).
- Den nejde vyzvednout před kalendářním dnem odměn (den 12 nejdřív 12.) ani nad délku měsíce (v únoru dny 29–31 propadají).
- Cooldown se počítá jen z claimů zdroje `claim`, ne z `gift` a `unlock`.

## Nasazení na produkci

Restart serveru je denně v 04:00, reset dne v 04:15. JAR nahraj přes den, migrace proběhne při nejbližším restartu. Verze 1.1.1 nemění schéma DB oproti 1.1.0.

1. **Záloha** složky `plugins/FDailyRewards` (plugin navíc sám udělá `database.db.bak-v1-<čas>` před migrací).
2. Smaž starý JAR, nahraj `deploy/FDailyRewards-1.1.1.jar`. **`config.yml` ani `rewardsTemplate.yml` neměň.** Nové klíče se berou z výchozího configu v JARu (prod config je jeho přesný prefix). Volitelně `deploy/config.yml` jako přehled všech klíčů.
3. Po restartu zkontroluj log: `Záloha databáze před migrací v1 -> v2`, `Načteno 31 dní odměn`, žádný `SEVERE`.
4. Rychlý test: `/fdailyrewards admin passinfo <ty>`, `/papi parse me %fdr_state%`, `/daily`.
5. **Převod držitelů Passu** (malý počet): `lp search permission survival.premium.dailylogin`, každému `fdailyrewards admin givepass <hráč> 1` (podle zbývajícího času klidně 2). Do této chvíle je drží `legacy_pass_permission`.
6. **Tebex** (Login Pass, Premium + Login, Měsíční Bundle): příkaz `lp user {username} permission settemp survival.premium.dailylogin true 30d accumulate` nahraď `fdailyrewards admin givepass {username} 1`. Premium Pass (tokeny, `survival.login.tokeny`) se nemění. Balíček „Další den“ se **nezakládá**: `giftnext` a `giftday` jsou jen pro AT.
7. **Hologram** `CMI/Saves/Holograms.yml` ř. 3171: `{luckperms_has_permission_survival.premium.dailylogin}` → `{fdr_vip_active}`.
8. Po týdnu bez problémů nastav `legacy_pass_permission: ""`, `/fdailyrewards reload`, a starou permission odeber.
9. **Neúspěšné nákupy Passu:** `plugins/FDailyRewards/failed-purchases.log` (čas, příkaz, hráč, UUID, důvod, oddělené tabulátorem) pravidelně kontroluj. Typicky jde o Pass koupený pro jméno, které na serveru nikdy nehrálo: zadej ho ručně správnému hráči (`givepass`), nebo vrať platbu. Pro upozornění na Discord nastav `failed_purchase_command`, např. `discordsrv broadcast #kanal Nákup {command} pro {player} selhal: {reason}`.

### Napojení na CiviaRank

- **Pořadí:** nejdřív krok 5 (`givepass` všem držitelům staré permission), **teprve potom** zapnout v CiviaRank čtení `%fdr_vip_expires_unix%`. Stará permission se v tomto placeholderu nepočítá, takže by držitelé bez převodu měli `0` (= bez Passu).
- **Offline hráči:** PlaceholderAPI vrací pro offline hráče `""`. Pokud CiviaRank potřebuje konec Passu offline hráče (např. při noční kontrole expirací), nesmí ho číst přes PAPI, ale přímo z pluginu (stačí `compileOnly` na JAR FDailyRewards):

  ```java
  var fdr = (com.fretka46.fDailyRewards.FDailyRewards) Bukkit.getPluginManager().getPlugin("FDailyRewards");
  if (fdr != null && fdr.isEnabled()) {
      var info = fdr.rewards().pass().info(uuid);      // funguje i pro offline hráče, čte DB
      java.time.Instant expires = info.expires();       // null = žádný zaplacený měsíc
  }
  ```

  Volat z hlavního vlákna nebo počítat s tím, že jde o dotaz do SQLite (pod zámkem, rychlý).

**Rollback:** zastav server, vrať starý JAR **a zálohu `database.db.bak-v1-…`**. Samotný starý JAR nad migrovanou DB nestačí (unikátní index podle nového sloupce `month` by mu odmítal zápisy), proto se přijde o claimy od migrace.

**Texty obchodu** (Login Pass, Premium + Login, Měsíční Bundle), navržené znění: „Platí do konce kalendářního měsíce. Koupí vícekrát se přidá další měsíc.“ a varování „Kupuješ poslední týden v měsíci? Pass platí jen do konce měsíce.“ Značky `[OVĚŘIT]` o 30 dnech platí až po nasazení.

## Na co dát pozor

- `giftnext_mode: any`: dar může být o den dopředu a běžné vyzvednutí pak čeká, až kalendář den doběhne (hráč za měsíc nezíská víc). Proto je výchozí `catchup`.
- Hromadné předání běží 1 den za tick. Když se server vypne uprostřed (nejvýš pár ticků), dny jsou zapsané jako vyzvednuté, ale zbytek odměn nedorazí (dořešit přes `resetday` + `unlockmissed`). Když se odpojí hráč, zbytek jde do fronty.
- `vip_expires_unix` a `vip_months_left` počítají jen Pass v DB, ne přechodovou permission.
- `setCustomModelData(int)` je v API 26.3 označené jako zastaralé (funguje, jako ve starém JARu). Nahradit až s větším zásahem do configu.
- `api-version: '26.3'` v `plugin.yml`.

## Testy

`gradle test` (JDK 25, 46 testů): všechny stavy `evaluate`, neaktivní hráč, VIP přeskok, kratší měsíc, okno 00:00–04:14, hranice měsíce, tři nákupy Passu za sebou, `giftnext`/`unlockmissed`, atomicita claimu, migrace staré DB (včetně duplicit) a záloha. Od 1.1.1 navíc `ReviewFixesTest`: režimy `giftnext`, log neúspěchů, přepnutí měsíce po resetu, čekání po VIP dni, `next_in`, fronta dárků, filtr hromadného předání, cache, načtení `config.yml` přes skutečné `YamlConfiguration` a mezery v Passu. S `-PprodDb=<cesta>` se migruje i kopie produkční DB.

Build: `gradle build` → `build/libs/FDailyRewards-1.1.1.jar`.
