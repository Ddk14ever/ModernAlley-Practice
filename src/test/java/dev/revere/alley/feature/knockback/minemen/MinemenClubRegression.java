package dev.revere.alley.feature.knockback.minemen;

import com.google.common.base.Function;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.combat.KitSettingOldSwordBlocking;
import dev.revere.alley.feature.knockback.KnockbackBranch;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.util.RayTraceResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Standalone regression cases. Compilation/execution is intentionally opt-in. */
public final class MinemenClubRegression {
    public static void main(String[] args) throws Exception {
        MinemenClubSettings settings = MinemenClubSettings.load(new YamlConfiguration());
        check(KnockbackBranch.fromName("MinemenClub") == KnockbackBranch.MINEMENCLUB,
                "Mixed-case branch name was rejected");
        check(KnockbackBranch.fromName("unknown") == null, "Unknown branch was accepted");

        Vector base = MinemenClubMotion.base(new Vector(), 0.0D, 2.0D, 2.0D, settings);
        vector(base, 0.0D, 0.25635D, 0.38488D, "Club default base");
        MinemenClubMotion.addMelee(base, 0.0F, settings);
        vector(base, 0.0D, 0.35635D, 0.88488D, "Every-hit melee extra");

        Vector inherited = MinemenClubMotion.base(new Vector(0.7D, -0.8D, -0.9D),
                0.0D, 2.0D, 2.0D, settings);
        close(inherited.getX(), 0.7D / 1.9055D, "Perpendicular X motion was projected away");
        close(inherited.getZ(), -0.9D / 1.9055D + 0.38488D, "Previous Z motion was lost");
        close(inherited.getY(), 0.25635D, "Base Y inherited falling motion");

        close(MinemenClubMotion.rangeReduction(settings, 3.0D), 0.0D, "Distance threshold");
        close(MinemenClubMotion.rangeReduction(settings, 3.0001D), 0.0450025D,
                "Club's threshold discontinuity was smoothed away");
        close(MinemenClubMotion.rangeReduction(settings, 20.0D), 0.12D, "Reduction cap");
        vector(MinemenClubMotion.base(new Vector(), 0.0D, 2.0D, 20.0D, settings),
                0.0D, 0.25635D, 0.26488D, "Capped distance reduction");

        // The upper hurt-window supplement and resisted base both retain old motion,
        // then still get EntityHuman's successful-melee extra without friction/limit.
        Vector supplement = new Vector(0.2D, -0.7D, -0.3D);
        MinemenClubMotion.addMelee(supplement, 0.0F, settings);
        vector(supplement, 0.2D, -0.6D, 0.2D, "Damage supplement / resisted base extra");
        check(!MinemenClubMotion.resistsBase(0.0D, 0.0D), "Zero resistance rejected a base");
        check(MinemenClubMotion.resistsBase(1.0D, 0.999D), "Full resistance accepted a base");
        check(MinemenClubMotion.resistsBase(0.5D, 0.49D), "Resistance probability below boundary");
        check(!MinemenClubMotion.resistsBase(0.5D, 0.5D), "Resistance probability at boundary");

        YamlConfiguration limitConfig = new YamlConfiguration();
        limitConfig.set("vertical", 5.0D);
        limitConfig.set("vertical-limit-value", 0.4D);
        MinemenClubSettings limited = MinemenClubSettings.load(limitConfig);
        Vector limit = MinemenClubMotion.base(new Vector(), 1.0D, 0.0D, 2.0D, limited);
        close(limit.getY(), 0.4D, "Base Y limit");
        MinemenClubMotion.addMelee(limit, 90.0F, limited);
        close(limit.getY(), 0.5D, "Melee Y was limited after the extra");
        close(limit.getX(), -0.11512D, "Extra must follow attacker yaw, not victim direction");
        limitConfig.set("vertical-limit", false);
        close(MinemenClubMotion.base(new Vector(), 1.0D, 0.0D, 2.0D,
                MinemenClubSettings.load(limitConfig)).getY(), 5.0D, "Disabled Y limit");

        Vector punch = new Vector();
        MinemenClubMotion.addPunch(punch, new Vector(3.0D, 9.0D, 4.0D), 2);
        vector(punch, 0.72D, 0.1D, 0.96D, "Punch direction/level and one vertical extra");
        Vector arrow = MinemenClubMotion.base(new Vector(), 0.0D, 2.0D, 2.0D, settings);
        MinemenClubMotion.addPunch(arrow, new Vector(1.0D, 0.0D, 0.0D), 2);
        vector(arrow, 1.2D, 0.35635D, 0.38488D, "Shared projectile base plus independent Punch");
        Vector noPunch = arrow.clone();
        MinemenClubMotion.addPunch(noPunch, new Vector(), 2);
        vector(noPunch, arrow.getX(), arrow.getY(), arrow.getZ(), "Zero flight Punch guard");

        YamlConfiguration attackerConfig = new YamlConfiguration();
        attackerConfig.set("sprint-multiplier", 2.0D);
        Vector extra = new Vector();
        MinemenClubMotion.addMelee(extra, 0.0F, MinemenClubSettings.load(attackerConfig));
        vector(extra, 0.0D, 0.1D, 1.0D, "Extra must use attacker profile");

        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("horizontal", Double.NaN);
        invalid.set("vertical", -1.0D);
        MinemenClubSettings repaired = MinemenClubSettings.load(invalid);
        close(repaired.horizontal(), 0.9055D, "Non-finite horizontal fallback");
        close(repaired.vertical(), 0.25635D, "Negative vertical fallback");

        frictionAndGravity();
        gravityModifierLifecycle();
        legacyGravityMotion();
        legacyGravityBridge();
        knockbackGravityFlight();
        profilesAndKitSelection();
        blockedDamageWindows();
        combatEventLifecycle();
        historicalHitRegistration();
        System.out.println("PASS: Club motion, friction, gravity modifiers/legacy prediction, branch isolation, damage windows, attack completion and historical acceptance");
    }

    private static void frictionAndGravity() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("horizontal", 0.5755D);
        MinemenClubSettings original = MinemenClubSettings.load(config);
        close(original.horizontalFriction(), 1.5755D, "Missing friction must preserve old Club inheritance");
        close(original.gravity(), 0.08D, "Default native gravity");

        config.set("friction.horizontal", 4.0D);
        config.set("gravity", 0.04D);
        MinemenClubSettings custom = MinemenClubSettings.load(config);
        Vector motion = MinemenClubMotion.base(new Vector(0.8D, -0.9D, -0.4D),
                0.0D, 2.0D, 2.0D, custom);
        vector(motion, 0.2D, 0.25635D, -0.1D + (float) 0.33208D,
                "Custom friction divides both inherited horizontal components only");
        MinemenClubMotion.addMelee(motion, 0.0F, custom);
        vector(motion, 0.2D, 0.35635D, 0.4D + (float) 0.33208D,
                "Melee extra must not be divided by friction");
        Vector arrow = MinemenClubMotion.base(new Vector(0.8D, -0.9D, -0.4D),
                0.0D, 2.0D, 2.0D, custom);
        MinemenClubMotion.addPunch(arrow, new Vector(1.0D, 0.0D, 0.0D), 2);
        vector(arrow, 1.4D, 0.35635D, -0.1D + (float) 0.33208D,
                "Shared projectile inheritance and undivided Punch");
        close(custom.gravity(), 0.04D, "Configured native gravity");

        config.set("horizontal", 0.9055D);
        close(MinemenClubSettings.load(config).horizontalFriction(), 4.0D,
                "Horizontal edits must not change explicit friction");
        close(custom.horizontal(), 0.5755D, "Reload must not mutate previous settings");
        config.set("friction.horizontal", 1.0D);
        Vector full = MinemenClubMotion.base(new Vector(0.8D, 3.0D, 0.0D),
                0.0D, 2.0D, 2.0D, MinemenClubSettings.load(config));
        close(full.getX(), 0.8D, "Friction=1 preserves all perpendicular motion");

        for (double invalid : new double[]{0.0D, -2.0D, Double.NaN, Double.POSITIVE_INFINITY}) {
            config.set("friction.horizontal", invalid);
            close(MinemenClubSettings.load(config).horizontalFriction(), 1.9055D,
                    "Invalid friction fallback");
        }
        for (double valid : new double[]{-1.0D, 0.0D, 0.08D, 1.0D}) {
            config.set("gravity", valid);
            close(MinemenClubSettings.load(config).gravity(), valid, "Native gravity bounds");
        }
        for (double invalid : new double[]{-1.01D, 1.01D, Double.NaN, Double.POSITIVE_INFINITY}) {
            config.set("gravity", invalid);
            close(MinemenClubSettings.load(config).gravity(), 0.08D, "Invalid gravity fallback");
        }
    }

    private static void gravityModifierLifecycle() {
        double[] base = {0.04D};
        Map<NamespacedKey, AttributeModifier> modifiers = new HashMap<>();
        AttributeModifier external = new AttributeModifier(new NamespacedKey("other", "gravity"),
                0.01D, AttributeModifier.Operation.ADD_NUMBER);
        modifiers.put(external.getKey(), external);
        AttributeInstance attribute = (AttributeInstance) Proxy.newProxyInstance(
                AttributeInstance.class.getClassLoader(), new Class<?>[]{AttributeInstance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBaseValue" -> base[0];
                    case "getModifier" -> modifiers.get(args[0]);
                    case "addTransientModifier" -> {
                        AttributeModifier modifier = (AttributeModifier) args[0];
                        check(modifiers.putIfAbsent(modifier.getKey(), modifier) == null,
                                "Gravity reload stacked a duplicate modifier");
                        yield null;
                    }
                    case "removeModifier" -> {
                        AttributeModifier modifier = (AttributeModifier) args[0];
                        modifiers.remove(modifier.getKey());
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected gravity attribute call: " + method.getName());
                });
        MinemenClubGravity.replace(attribute, 0.02D);
        close(base[0], 0.04D, "Gravity must not overwrite original base");
        close(base[0] + modifiers.values().stream().mapToDouble(AttributeModifier::getAmount).sum(),
                0.03D, "Native gravity preserves other plugin modifiers");
        MinemenClubGravity.replace(attribute, 0.06D);
        check(modifiers.size() == 2, "Gravity reload duplicated or removed a modifier");
        close(base[0] + modifiers.values().stream().mapToDouble(AttributeModifier::getAmount).sum(),
                0.07D, "Gravity reload did not replace the old amount");
        base[0] = 0.09D; // Another plugin changes the base during the match.
        MinemenClubGravity.remove(attribute);
        check(modifiers.size() == 1 && modifiers.get(external.getKey()) == external,
                "Leaving the match removed another plugin's modifier");
        close(base[0], 0.09D, "Leaving the match reverted another plugin's base change");
        MinemenClubGravity.remove(attribute); // Repeated leave/quit/shutdown is harmless.
        MinemenClubGravity.replace(attribute, base[0]);
        check(modifiers.size() == 1, "Unchanged gravity should not add a zero modifier");
    }

    private static void legacyGravityMotion() {
        Vector accepted = new Vector(0.3D, 0.42D, -0.2D);
        Vector next = MinemenClubGravityMotion.next(accepted, 0.04D);
        vector(next, 0.3D * (double) 0.91F, 0.38D * (double) 0.98F,
                -0.2D * (double) 0.91F, "Air drag must follow accepted movement, not on-hit friction");
        vector(accepted, 0.3D, 0.42D, -0.2D, "Gravity prediction mutated accepted movement");
        Vector normal = new Vector(0.3D, -0.08D, -0.2D);
        Vector low = normal.clone();
        for (int tick = 0; tick < 8; tick++) {
            normal = MinemenClubGravityMotion.next(normal, 0.08D);
            low = MinemenClubGravityMotion.next(low, 0.04D);
        }
        check(normal.getY() < low.getY() && low.getY() < 0.0D,
                "Lower descent gravity must reduce falling acceleration");
        close(normal.getX(), low.getX(), "Custom gravity changed horizontal flight");
        close(normal.getZ(), low.getZ(), "Custom gravity changed lateral flight");
        // The actual old-client path adds only the gravity difference. It must
        // match custom vertical acceleration without assigning delayed X/Z.
        for (double gravity : new double[]{-1.0D, -0.04D, 0.0D, 0.04D, 0.067D, 0.08D, 0.12D, 1.0D}) {
            double delta = MinemenClubGravityMotion.correction(gravity);
            for (double falling : new double[]{-0.01D, -0.2D, -1.5D}) {
                Vector current = new Vector(0.17D, falling, -0.33D);
                Vector vanilla = MinemenClubGravityMotion.next(current, 0.08D);
                Vector custom = MinemenClubGravityMotion.next(current, gravity);
                vanilla.add(new Vector(0.0D, delta, 0.0D));
                vector(vanilla, custom.getX(), custom.getY(), custom.getZ(),
                        "Additive correction must affect only vertical acceleration");
                check(Math.abs((double) (float) delta - delta) < 1.0E-7D,
                        "Legacy explosion float precision lost the gravity correction");
            }
        }
        close(MinemenClubGravityMotion.correction(0.08D), 0.0D,
                "Vanilla gravity must not add an impulse");
        Vector zero = MinemenClubGravityMotion.next(new Vector(0.0D, -0.2D, 0.0D), 0.0D);
        close(zero.getY(), -0.2D * (double) 0.98F, "Zero gravity must retain drag and existing Y");
        check(MinemenClubGravityMotion.next(new Vector(), -0.04D).getY() > 0.0D,
                "Negative gravity did not accelerate upward");
        check(MinemenClubGravityMotion.next(new Vector(0.0D, -0.2D, 0.0D), 0.12D).getY()
                        < MinemenClubGravityMotion.next(new Vector(0.0D, -0.2D, 0.0D), 0.08D).getY(),
                "High gravity did not increase falling acceleration");
        check(!MinemenClubGravityMotion.finite(new Vector(Double.NaN, 0.0D, 0.0D)),
                "Non-finite gravity packet accepted");
        check(!MinemenClubGravityMotion.finite(new Vector(0.0D, Double.POSITIVE_INFINITY, 0.0D)),
                "Infinite gravity packet accepted");
        check(MinemenClubGravityCompensation.graceNanos(150) == 250_000_000L,
                "External impulse grace must include the movement round trip");
        check(MinemenClubGravityCompensation.graceNanos(-1) == 100_000_000L,
                "Unknown ping produced a negative external impulse grace");
    }

    private static void legacyGravityBridge() throws ReflectiveOperationException {
        GravityHandle handle = new GravityHandle();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{GravityBridgePlayer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getHandle")) return handle;
                    throw new AssertionError("Unexpected Bukkit gravity velocity read: " + method.getName());
                });
        MinemenClubGravityBridge bridge = new MinemenClubGravityBridge();
        check(bridge.sample(player) == null, "No-movement shared ZERO must not seed compensation");
        handle.known = new GravityVector(0.3D, 0.42D, -0.2D);
        MinemenClubGravityBridge.Sample sample = bridge.sample(player);
        vector(sample.movement(), 0.3D, 0.42D, -0.2D, "Read accepted client movement");
        check(bridge.sample(player).token() == sample.token(), "Reused movement lost identity");
        handle.known = new GravityVector(0.3D, 0.42D, -0.2D);
        check(bridge.sample(player).token() != sample.token(),
                "A newly accepted identical displacement must remain a new sample");
        handle.known = new GravityVector(0.0D, 0.0D, 0.0D);
        check(bridge.sample(player) != null, "Accepted zero displacement was confused with absent movement");
    }

    private static void knockbackGravityFlight() {
        Vector before = new Vector(0.1D, -0.2D, 0.3D);
        check(!MinemenClubGravityFlight.hasImpulse(before, before.clone()),
                "Resisted unchanged velocity must not authorize custom gravity");
        check(!MinemenClubGravityFlight.hasImpulse(before, new Vector()),
                "A stopped velocity must not authorize a later natural fall");
        check(!MinemenClubGravityFlight.hasImpulse(before, new Vector(Double.NaN, 0.3D, 0.0D)),
                "Invalid delivered velocity authorized custom gravity");
        check(MinemenClubGravityFlight.hasImpulse(before, new Vector(0.2D, 0.35635D, 0.3D)),
                "Delivered melee impulse did not authorize descent tracking");

        MinemenClubGravityFlight flight = new MinemenClubGravityFlight(100L);
        check(flight.phase() == MinemenClubGravityFlight.Phase.ARMED,
                "An assigned hit must not immediately change gravity");
        flight.update(10L, true, 0.0D, new Object());
        check(flight.phase() == MinemenClubGravityFlight.Phase.ARMED,
                "Pre-launch ground state ended the delayed knockback too early");
        Object preHitFall = new Object();
        flight.update(99L, false, -0.3D, preHitFall);
        flight.update(100L, false, -0.3D, preHitFall);
        check(flight.phase() == MinemenClubGravityFlight.Phase.ARMED,
                "A stale pre-hit falling sample enabled custom gravity");
        flight.update(101L, false, 0.2D, new Object());
        flight.update(102L, false, 0.0D, new Object());
        check(flight.phase() == MinemenClubGravityFlight.Phase.ARMED,
                "Knockback ascent or apex was modified");
        flight.update(103L, false, -0.01D, new Object());
        check(flight.phase() == MinemenClubGravityFlight.Phase.DESCENDING,
                "Accepted post-hit descent did not enable custom gravity");
        flight.update(104L, false, 0.0D, null);
        check(flight.phase() == MinemenClubGravityFlight.Phase.DESCENDING,
                "Missing movement spuriously ended the current descent");
        flight.update(105L, true, 0.0D, null);
        check(flight.phase() == MinemenClubGravityFlight.Phase.ENDED,
                "Landing did not end knockback gravity without a fresh sample");
        flight.update(106L, false, -0.2D, new Object());
        check(flight.phase() == MinemenClubGravityFlight.Phase.ENDED,
                "A later natural fall reused an old knockback authorization");

        MinemenClubGravityFlight rehit = new MinemenClubGravityFlight(200L);
        rehit.update(201L, false, 0.3D, new Object());
        check(rehit.phase() == MinemenClubGravityFlight.Phase.ARMED,
                "A second hit inherited the previous descent phase");
        rehit.update(202L, false, -0.1D, new Object());
        rehit.update(203L, false, 0.1D, new Object());
        check(rehit.phase() == MinemenClubGravityFlight.Phase.ENDED,
                "An upward bounce/new jump kept the previous falling gravity");
        MinemenClubGravityFlight blocked = new MinemenClubGravityFlight(100L);
        blocked.update(100L, true, 0.0D, new Object());
        check(blocked.phase() == MinemenClubGravityFlight.Phase.ENDED,
                "A hit that never left the ground must not affect a later jump");
    }

    public interface GravityBridgePlayer extends Player {
        GravityHandle getHandle();
    }

    public static final class GravityHandle {
        GravityVector known = GravityVector.ZERO;

        public GravityVector getKnownMovement() {
            return known;
        }
    }

    public static final class GravityVector {
        public static final GravityVector ZERO = new GravityVector(0.0D, 0.0D, 0.0D);
        public final double x;
        public final double y;
        public final double z;

        GravityVector(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static void blockedDamageWindows() {
        Player victim = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected player call: " + method.getName());
                });
        MinemenClubDamageWindow window = new MinemenClubDamageWindow(0.0D);
        MinemenClubDamageWindow.Hit first = window.evaluate(7.0D, false);
        EntityDamageByEntityEvent blocked = damageEvent(victim, 7.0D, 0.0D, 0.0D);
        MinemenClubDamageModifiers.rebuild(blocked, first.damage(), true, true);
        close(blocked.getFinalDamage(), 4.0D, "First sword block");
        window.accept(first);
        close(window.lastDamage(), 7.0D, "Remember pre-block damage, not four");
        check(!window.evaluate(7.0D, true).accepted(), "Holding/releasing block reopened equal-damage hits");
        check(!window.evaluate(4.0D, true).accepted(), "Lower damage bypassed hit delay");

        MinemenClubDamageWindow.Hit stronger = window.evaluate(10.5D, true);
        close(stronger.damage(), 3.5D, "Subtract full previous damage before blocking");
        EntityDamageByEntityEvent supplement = damageEvent(victim, 10.5D, 0.4D, 1.0D);
        supplement.setDamage(DamageModifier.INVULNERABILITY_REDUCTION, -4.0D);
        MinemenClubDamageModifiers.rebuild(supplement, stronger.damage(), true, true);
        close(supplement.getDamage(DamageModifier.BLOCKING), -1.25D, "Block the supplement only");
        close(supplement.getDamage(DamageModifier.INVULNERABILITY_REDUCTION), 0.0D,
                "Modern invulnerability reduction was applied twice");
        close(supplement.getFinalDamage(), 0.35D, "Recalculate armour and absorption after delta/block");
        window.accept(stronger);
        close(window.lastDamage(), 10.5D, "A supplement must remember complete damage");

        window.evaluate(20.0D, true); // Cancelled downstream: do not commit.
        close(window.evaluate(11.0D, true).damage(), 0.5D, "Cancelled damage changed the window");
        MinemenClubDamageWindow.Hit fresh = window.evaluate(7.0D, false);
        check(fresh.accepted() && !fresh.supplement(), "Expired upper half rejected a full hit");
        EntityDamageByEntityEvent unblocked = damageEvent(victim, 7.0D, 0.0D, 0.0D);
        unblocked.setDamage(DamageModifier.BLOCKING, -7.0D);
        MinemenClubDamageModifiers.rebuild(unblocked, fresh.damage(), true, false);
        close(unblocked.getFinalDamage(), 7.0D, "Released sword retained native blocking");
    }

    private static void combatEventLifecycle() {
        int[] ticks = {0};
        double[] last = {0.0D};
        boolean[] enabled = {true};
        boolean[] inRange = {true};
        boolean[] raised = {true};
        int[] executions = {0};
        Runnable[] nativeAttack = {() -> { }};
        UUID victimId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        Player victim = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> victimId;
                    case "isDead" -> false;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "getMaximumNoDamageTicks" -> 20;
                    case "getLastDamage" -> last[0];
                    case "setLastDamage" -> { last[0] = (double) args[0]; yield null; }
                    case "setNoDamageTicks" -> { ticks[0] = (int) args[0]; yield null; }
                    default -> throw new AssertionError("Unexpected victim call: " + method.getName());
                });
        Player attacker = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> attackerId;
                    case "hasActiveItem" -> raised[0];
                    case "clearActiveItem" -> { raised[0] = false; yield null; }
                    case "attack" -> { executions[0]++; nativeAttack[0].run(); yield null; }
                    default -> throw new AssertionError("Unexpected attacker call: " + method.getName());
                });
        KnockbackProfile club = new KnockbackProfile("MinemenClub", new YamlConfiguration());
        KnockbackManager manager = new KnockbackManager() {
            @Override public boolean isMinemenClubKnockback(Player player) { return enabled[0]; }
            @Override public KnockbackProfile getAppliedProfile(Player player) { return club; }
            @Override public boolean wasInsideHurtResistanceWindow(Player player) { return ticks[0] > 10; }
            @Override public boolean isInsideHurtResistanceWindow(Player player) { return ticks[0] > 10; }
            @Override public boolean canMinemenClubAttack(Player from, Player to) { return inRange[0]; }
        };
        MinemenClubCombatListener listener = new MinemenClubCombatListener(manager, () -> null);
        EntityDamageByEntityEvent first = damageEvent(victim, 7.0D, 0.0D, 0.0D);
        listener.prepare(first);
        first.setDamage(0.0D); // Boxing/Sumo changes damage after acceptance.
        listener.finishDamage(first);
        check(ticks[0] == 20 && last[0] == 7.0D, "NoDamage lost original damage / hit delay");
        ticks[0] = 19;
        EntityDamageByEntityEvent repeated = damageEvent(victim, 7.0D, 0.0D, 0.0D);
        listener.prepare(repeated);
        check(repeated.isCancelled(), "NoDamage allowed repeated equal damage impulses");
        listener.finishDamage(repeated);
        EntityDamageByEntityEvent cancelled = damageEvent(victim, 10.5D, 0.0D, 0.0D);
        listener.prepare(cancelled);
        close(cancelled.getDamage(), 3.5D, "Accepted event did not use the supplement");
        cancelled.setCancelled(true);
        listener.finishDamage(cancelled);
        check(last[0] == 7.0D && ticks[0] == 19, "Cancelled hit committed damage / extended delay");
        EntityDamageByEntityEvent retry = damageEvent(victim, 10.5D, 0.0D, 0.0D);
        listener.prepare(retry);
        listener.finishDamage(retry);
        check(last[0] == 10.5D && ticks[0] == 19, "Supplement restarted delay or forgot full amount");

        nativeAttack[0] = () -> close(last[0], 0.0D, "Native gate hid legacy damage correction");
        listener.attack(attacker, victim); // NMS declines the attack without a damage event.
        check(executions[0] == 1 && !raised[0] && last[0] == 10.5D,
                "Rejected melee must execute once, restore last damage and release block");
        raised[0] = true;
        inRange[0] = false;
        listener.attack(attacker, victim);
        check(executions[0] == 1 && raised[0], "Out-of-range packet executed / stopped blocking");
        inRange[0] = true;
        nativeAttack[0] = () -> { throw new IllegalStateException("test native failure"); };
        try {
            listener.attack(attacker, victim);
            throw new AssertionError("Native failure was swallowed");
        } catch (IllegalStateException expected) {
            check(!raised[0] && last[0] == 10.5D, "Attack failure skipped cleanup");
        }

        listener.clearPlayer(victimId);
        ticks[0] = 0;
        EntityDamageByEntityEvent afterReset = damageEvent(victim, 3.0D, 0.0D, 0.0D);
        listener.prepare(afterReset);
        listener.finishDamage(afterReset);
        close(last[0], 3.0D, "Previous match window leaked across reset");
        enabled[0] = false;
        EntityDamageByEntityEvent other = damageEvent(victim, 2.0D, 0.0D, 0.0D);
        listener.prepare(other);
        check(!other.isCancelled() && other.getDamage() == 2.0D, "Combat changes leaked into another branch");
    }

    private static EntityDamageByEntityEvent damageEvent(Player victim, double base,
                                                         double armour, double absorption) {
        Map<DamageModifier, Double> values = new EnumMap<>(DamageModifier.class);
        Map<DamageModifier, Function<Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        for (DamageModifier modifier : DamageModifier.values()) {
            values.put(modifier, 0.0D);
            functions.put(modifier, ignored -> 0.0D);
        }
        values.put(DamageModifier.BASE, base);
        functions.put(DamageModifier.ARMOR, incoming -> -incoming * armour);
        functions.put(DamageModifier.ABSORPTION, incoming -> -Math.min(incoming, absorption));
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected damage source call: " + method.getName());
                });
        return new EntityDamageByEntityEvent(victim, victim, DamageCause.ENTITY_ATTACK,
                source, values, functions);
    }

    private static void historicalHitRegistration() {
        int[] rays = {0};
        int[] clearAt = {0};
        World world = testWorld(rays, clearAt);
        World otherWorld = testWorld(new int[]{0}, new int[]{0});
        MinemenClubPositionHistory positions = new MinemenClubPositionHistory();
        Location current = new Location(world, 77.0D, 0.0D, 0.0D);
        Location sample = new Location(world, 0.0D, 0.0D, 0.0D);
        positions.record(sample, 1000L);
        sample.setX(999.0D);
        positions.record(new Location(world, 99.0D, 0.0D, 0.0D), 1029L);
        close(positions.rewind(current, 0, 1092L).getX(), 0.0D, "Sample clone / 30 ms minimum");
        positions.record(new Location(world, 3.0D, 0.0D, 0.0D), 1030L);
        positions.record(new Location(world, 6.0D, 0.0D, 0.0D), 1060L);
        close(positions.rewind(current, 0, 1152L).getX(), 6.0D, "Club 92 ms offset");
        close(positions.rewind(current, 15, 1152L).getX(), 4.5D, "Ping plus offset interpolation");
        close(positions.rewind(current, 100, 1152L).getX(), 77.0D, "Insufficient history uses current position");
        positions.record(new Location(otherWorld, 20.0D, 0.0D, 0.0D), 1090L);
        close(positions.rewind(current, 0, 1200L).getX(), 77.0D, "Cross-world history was interpolated");
        positions = new MinemenClubPositionHistory();
        for (int i = 0; i < 45; i++) positions.record(new Location(world, i, 0, 0), 20000L + i * 30L);
        close(positions.rewind(current, 1808, 22000L).getX(), 77.0D, "History retained more than 41 samples");
        close(positions.rewind(current, 1788, 22000L).getX(), 4.0D, "Oldest of 41 samples was discarded");

        Location eye = new Location(world, 0.0D, 1.62D, 0.0D);
        Location feet = new Location(world, 0.0D, 0.0D, 2.0D);
        for (int clear = 1; clear <= 3; clear++) {
            rays[0] = 0;
            clearAt[0] = clear;
            check(MinemenClubHitRegistration.hasLineOfSight(eye, feet, 1.62D), "One clear height was rejected");
            check(rays[0] == clear, "Three-height check did not short-circuit");
        }
        rays[0] = 0;
        clearAt[0] = 0;
        check(!MinemenClubHitRegistration.hasLineOfSight(eye, feet, 1.62D) && rays[0] == 3,
                "Fully obstructed target was visible");
        check(MinemenClubHitRegistration.acceptsDistance(36.0D, true), "Visible boundary must be inclusive");
        check(!MinemenClubHitRegistration.acceptsDistance(36.0001D, true), "Visible cap exceeded");
        check(MinemenClubHitRegistration.acceptsDistance(12.75D, false), "Obstructed boundary rejected");
        check(!MinemenClubHitRegistration.acceptsDistance(12.7501D, false), "Obstructed cap exceeded");
        check(!MinemenClubHitRegistration.acceptsDistance(Double.NaN, true), "Non-finite distance accepted");
    }

    private static World testWorld(int[] rays, int[] clearAt) {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "rayTraceBlocks" -> {
                        rays[0]++;
                        Location from = (Location) args[0];
                        Vector direction = (Vector) args[1];
                        double length = (double) args[2];
                        close(from.getY() + direction.getY() * length,
                                1.62D * (4 - rays[0]) / 3.0D, "Wrong sight target height");
                        yield rays[0] == clearAt[0] ? null : new RayTraceResult(new Vector());
                    }
                    default -> throw new AssertionError("Unexpected world call: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static void profilesAndKitSelection() throws Exception {
        YamlConfiguration club = new YamlConfiguration();
        club.set("branch", "MinemenClub");
        club.set("stop_sprint", true);
        club.set("disable_downward_kb", true);
        club.set("packet.misplace.enabled", true);
        club.set("packet.misplace.hit_compensation", true);
        club.set("packet.delay.enabled", true);
        club.set("modern.cooldown_affects_kb", true);
        club.set("modern.entity_interaction_range", 2.9D);
        KnockbackProfile profile = new KnockbackProfile("MinemenClub", club);
        check(!profile.isStopSprint() && !profile.isDisableDownwardKb()
                && !profile.isPacketMisplaceEnabled() && !profile.isPacketMisplaceHitCompensation()
                && !profile.isPacketDelayEnabled() && !profile.isCooldownAffectsKb(),
                "Legacy/modern mechanisms leaked into Club");
        close(profile.getEntityInteractionRange(), 3.0D, "Club client range retained old 2.9 setting");

        YamlConfiguration old = new YamlConfiguration();
        old.set("branch", "LEGACY");
        old.set("stop_sprint", true);
        old.set("horizontal.ground", 0.61D);
        KnockbackProfile legacy = new KnockbackProfile("1_8", old);
        check(legacy.isStopSprint() && legacy.isDisableDownwardKb(), "Legacy defaults changed");
        close(legacy.getHorizontalGround(), 0.61D, "Legacy nested horizontal setting changed");
        KnockbackProfile modern = new KnockbackProfile("default", new YamlConfiguration());
        close(modern.getHorizontalGround(), 0.35D, "Default horizontal fallback changed");

        KnockbackManager manager = new KnockbackManager();
        Field field = KnockbackManager.class.getDeclaredField("profiles");
        field.setAccessible(true);
        Map<String, KnockbackProfile> profiles = (Map<String, KnockbackProfile>) field.get(manager);
        profiles.put("minemenclub", profile);
        profiles.put("1_8", legacy);
        profiles.put("default", modern);
        Kit kit = new Kit("regression", "regression", "", "", "", null, null, 0, null, null, null);
        KitSettingOldSwordBlocking setting = new KitSettingOldSwordBlocking();
        kit.addKitSetting(setting);
        kit.setKnockbackProfile("MinemenClub.yml");
        check(manager.resolveProfile(kit) == profile && manager.usesLegacyCombat(kit),
                "Club must select old combat without the old toggle");
        setting.setEnabled(true);
        check(manager.resolveProfile(kit) == profile, "Old toggle overrode explicit Club profile");
        kit.setKnockbackProfile("1_8");
        check(manager.resolveProfile(kit) == legacy, "Legacy kit selection changed");
        setting.setEnabled(false);
        kit.setKnockbackProfile("default");
        check(manager.resolveProfile(kit) == modern && !manager.usesLegacyCombat(kit),
                "Default kit selection changed");
        check(manager.getFallbackProfile(KnockbackBranch.MINEMENCLUB) == profile,
                "Club fallback selected another branch");
    }

    private static void vector(Vector vector, double x, double y, double z, String message) {
        close(vector.getX(), x, message + " (X)");
        close(vector.getY(), y, message + " (Y)");
        close(vector.getZ(), z, message + " (Z)");
    }

    private static void close(double actual, double expected, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1.0E-7D) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
