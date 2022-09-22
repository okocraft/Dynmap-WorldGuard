package org.dynmap.worldguard;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionType;
import com.sk89q.worldguard.util.profile.cache.ProfileCache;
import java.util.Collections;
import org.bukkit.Bukkit;
import org.dynmap.markers.AreaMarker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdateTask implements Runnable {

    final Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */
    private final DynmapWorldGuardPlugin plugin;
    List<World> worldsToDo;
    List<ProtectedRegion> regionsToDo;
    World curworld;


    UpdateTask(DynmapWorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // If worlds list isn't primed, prime it
        if (worldsToDo == null) {
            worldsToDo = new ArrayList<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                worldsToDo.add(WorldGuard.getInstance().getPlatform().getMatcher().getWorldByName(world.getName()));
            }
        }

        while (regionsToDo == null) {  // No pending regions for world
            if (worldsToDo.isEmpty()) { // No more worlds?
                /* Now, review old map - anything left is gone */
                for (AreaMarker oldm : plugin.resareas.values()) {
                    oldm.deleteMarker();
                }
                /* And replace with new map */
                plugin.resareas = newmap;
                // Set up for next update (new job)
                plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new UpdateTask(plugin), getUpdatesPeriod());
                return;
            } else {
                curworld = worldsToDo.remove(0);
                RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(curworld); /* Get region manager for world */
                if (rm != null) {
                    Map<String, ProtectedRegion> regions = rm.getRegions();  /* Get all the regions */
                    if (!regions.isEmpty()) {
                        regionsToDo = new ArrayList<>(regions.values());
                    }
                }
            }
        }
        /* Now, process up to limit regions */
        int updatesPerTick = plugin.getInt("updates-per-tick", 20);
        for (int i = 0; i < updatesPerTick; i++) {
            if (regionsToDo.isEmpty()) {
                regionsToDo = null;
                break;
            }
            ProtectedRegion pr = regionsToDo.remove(regionsToDo.size() - 1);
            int depth = 1;
            ProtectedRegion p = pr;
            while (p.getParent() != null) {
                depth++;
                p = p.getParent();
            }
            if (depth > plugin.getInt("maxdepth", 16)) {
                continue;
            }
            handleRegion(curworld, pr, newmap);
        }
        // Tick next step in the job
        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, this, 1);
    }

    private String formatInfoWindow(ProtectedRegion rg, AreaMarker m) {
        ProfileCache pc = WorldGuard.getInstance().getProfileCache();
        String v = "<div class=\"regioninfo\">" + getInfoWindow() + "</div>";
        v = v.replace("%regionname%", m.getLabel())
                .replace("%playerowners%", rg.getOwners().toPlayersString(pc))
                .replace("%groupowners%", rg.getOwners().toGroupsString())
                .replace("%playermembers%", rg.getMembers().toPlayersString(pc))
                .replace("%groupmembers%", rg.getMembers().toGroupsString())
                .replace("%parent%", rg.getParent() == null ? "" : rg.getParent().getId())
                .replace("%priority%", String.valueOf(rg.getPriority()));

        Map<Flag<?>, Object> map = rg.getFlags();
        StringBuilder flgs = new StringBuilder();
        for (Flag<?> f : map.keySet()) {
            flgs.append(f.getName()).append(": ").append(map.get(f).toString()).append("<br/>");
        }
        v = v.replace("%flags%", flgs.toString());
        return v;
    }

    private boolean isVisible(String id, String worldname) {
        if ((plugin.visible != null) && (plugin.visible.size() > 0)) {
            if ((!plugin.visible.contains(id))
                    && (!plugin.visible.contains("world:" + worldname)) && (!plugin.visible.contains(worldname + "/" + id))) {
                return false;
            }
        }
        if ((plugin.hidden != null) && (plugin.hidden.size() > 0)) {
            return !plugin.hidden.contains(id)
                    && !plugin.hidden.contains("world:" + worldname)
                    && !plugin.hidden.contains(worldname + "/" + id);
        }
        return true;
    }

    private static double cross(BlockVector2 p1, BlockVector2 p2) {
        return p1.getX() * p2.getZ() - p1.getZ() * p2.getX();
    }

    private static double calcAreaOfPolygon(List<BlockVector2> points) {
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            area += cross(points.get(i), points.get((i + 1) % points.size()));
        }
        return area / 2.0;
    }

    /**
     * Calc loop direction of given polygon.
     *
     * @param points Polygon points.
     *
     * @return When returns 1 it is clockwise, when returns -1 it is anticlockwise.
     *         Other than that, polygon is collapsed.
     */
    private static int getPolygonLoop(List<BlockVector2> points) {
        double area = calcAreaOfPolygon(points);
        if (area > 0) {
            return 1;
        } else if (area < 0) {
            return -1;
        } else {
            return 0;
        }
    }

    private static List<BlockVector2> expandPolygonXZByOne(List<BlockVector2> points) {
        List<BlockVector2> pointsCopy = new ArrayList<>(points);
        int loop = getPolygonLoop(points);
        if (loop == 0) {
            return pointsCopy;
        }
        if (loop != 1) {
            Collections.reverse(pointsCopy);
        }

        List<BlockVector2> result = new ArrayList<>();
        for (int i = 0; i < pointsCopy.size(); i++) {
            int xPrev = pointsCopy.get((i - 1 + pointsCopy.size()) % pointsCopy.size()).getX();
            int zPrev = pointsCopy.get((i - 1 + pointsCopy.size()) % pointsCopy.size()).getZ();
            int xCur = pointsCopy.get(i).getX();
            int zCur = pointsCopy.get(i).getZ();
            int xNext = pointsCopy.get((i + 1) % pointsCopy.size()).getX();
            int zNext = pointsCopy.get((i + 1) % pointsCopy.size()).getZ();

            int xCurNew = xCur;
            int zCurNew = zCur;

            if (zPrev < zCur || zCur < zNext) {
                xCurNew++;
            }
            if (xCur < xPrev || xNext < xCur) {
                zCurNew++;
            }

            result.add(BlockVector2.at(xCurNew, zCurNew));
        }
        return result;
    }

    /* Handle specific region */
    void handleRegion(World world, ProtectedRegion region, Map<String, AreaMarker> newmap) {
        if (!isVisible(region.getId(), world.getName())) {
            return;
        }
        if (region.getType() != RegionType.CUBOID && region.getType() != RegionType.POLYGON) {
            return;
        }

        List<BlockVector2> points = expandPolygonXZByOne(region.getPoints());
        double[] x = new double[points.size()];
        double[] z = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            x[i] = points.get(i).getX();
            z[i] = points.get(i).getZ();
        }

        String name = region.getId();
        name = name.substring(0, 1).toUpperCase() + name.substring(1);

        String markerId = world.getName() + "_" + region.getId();
        AreaMarker m = plugin.resareas.remove(markerId); /* Existing area? */
        if (m == null) {
            m = plugin.set.createAreaMarker(markerId, name, false, world.getName(), x, z, false);
            if (m == null) {
                return;
            }
        } else {
            m.setCornerLocations(x, z); /* Replace corner locations */
            m.setLabel(name);   /* Update label */
        }
        if (plugin.getBoolean("use3dregions")) { /* If 3D? */
            m.setRangeY(region.getMaximumPoint().getY() + 1.0, region.getMinimumPoint().getY());
        }

        /* Set line and fill properties */
        addStyle(m, region);

        /* Build popup */
        String desc = formatInfoWindow(region, m);
        m.setDescription(desc); /* Set popup */

        /* Add to map */
        newmap.put(markerId, m);
    }

    private void addStyle(AreaMarker m, ProtectedRegion region) {
        AreaStyle as = plugin.defstyle;

        boolean unowned = (region.getOwners().getPlayers().size() == 0) &&
                (region.getOwners().getUniqueIds().size() == 0) &&
                (region.getOwners().getGroups().size() == 0);
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if (unowned)
                sc = Integer.parseInt(as.unownedstrokecolor.substring(1), 16);
            else
                sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException ignored) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if (as.label != null) {
            m.setLabel(as.label);
        }
        if (plugin.boost_flag != null) {
            Boolean b = region.getFlag(plugin.boost_flag);
            m.setBoostFlag((b != null) && b);
        }
    }

    private String getInfoWindow() {
        return plugin.getString("infowindow",
                "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"" +
                        "font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>");
    }

    private long getUpdatesPeriod() {
        int per = plugin.getInt("update.period", 300);
        if (per < 15) per = 15;
        return per * 20L;
    }
}