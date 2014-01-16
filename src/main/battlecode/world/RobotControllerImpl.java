package battlecode.world;

import static battlecode.common.GameActionExceptionType.NOT_ACTIVE;
import static battlecode.common.GameActionExceptionType.CANT_DO_THAT_BRO;
import static battlecode.common.GameActionExceptionType.CANT_SENSE_THAT;
import static battlecode.common.GameActionExceptionType.MISSING_UPGRADE;
import static battlecode.common.GameActionExceptionType.NOT_ENOUGH_RESOURCE;
import static battlecode.common.GameActionExceptionType.NO_ROBOT_THERE;
import static battlecode.common.GameActionExceptionType.OUT_OF_RANGE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameActionExceptionType;
import battlecode.common.GameConstants;
import battlecode.common.GameObject;
import battlecode.common.MapLocation;
import battlecode.common.MovementType;
import battlecode.common.Robot;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotLevel;
import battlecode.common.RobotType;
import battlecode.common.Team;
import battlecode.common.TerrainTile;
import battlecode.common.Upgrade;
import battlecode.engine.GenericController;
import battlecode.engine.instrumenter.RobotDeathException;
import battlecode.engine.instrumenter.RobotMonitor;
import battlecode.world.signal.AttackSignal;
import battlecode.world.signal.CaptureSignal;
import battlecode.world.signal.HatSignal;
import battlecode.world.signal.IndicatorStringSignal;
import battlecode.world.signal.MatchObservationSignal;
import battlecode.world.signal.MinelayerSignal;
import battlecode.world.signal.MinelayerSignal.MineAction;
import battlecode.world.signal.MovementSignal;
import battlecode.world.signal.ResearchSignal;
import battlecode.world.signal.SpawnSignal;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;


/*
TODO:
- tweak player
-specs & software page?
- performance tips
- figure out why it's not deterministic

- optimize
- non-constant java.util costs
- player's navigation class, and specs about it
- fix execute action hack w/ robotmonitor ??
- fix playerfactory/spawnsignal hack??
- better suicide() ??
- pare down GW, GWviewer methods; add engine.getallsignals?

- TEST energon transfer every round, with cap
- TEST better println tagging
- TEST optimized silencing
- TEST "control ints"
- TEST indicator strings
- TEST map offsets
- TEST tiebreaking conditions
- TEST senseNearbyUpgrades
- TEST no battery freeze
- TEST upgrades
- TEST upgrades on map
- TEST only one energonchangesignal / round / robot
- TEST exception polling for action queue
- TEST fix action timing
- TEST action queue
- TEST broadcast queue
- TEST emp detonate
- TEST locating enemy batteries -- return direction
- TEST InternalArchon
- TEST energon decay
- TEST new energon transfer
- TEST map parsing
- TEST run perl script to get robottypes
- TEST serializable stuff
- TEST getNextMessage arrays
- TEST responding to signals
- TEST clock
 */
public class RobotControllerImpl extends ControllerShared implements RobotController, GenericController {

    public RobotControllerImpl(GameWorld gw, InternalRobot r) {
        super(gw, r);
    }
    
    public void assertHaveResource(double amount) throws GameActionException {
    	if (amount > gameWorld.resources(getTeam()))
    		throw new GameActionException(NOT_ENOUGH_RESOURCE, "You do not have enough MILK to do that.");
    }
    
    public void assertHaveUpgrade(Upgrade upgrade) throws GameActionException {
    	if (!gameWorld.hasUpgrade(getTeam(), upgrade))
    		throw new GameActionException(MISSING_UPGRADE, "You need the following upgrade: "+upgrade);
    }
    
    public void assertIsEncampment(MapLocation loc) throws GameActionException {
    	if (!gameWorld.isEncampment(loc))
    		throw new GameActionException(NO_ROBOT_THERE, "That location is not an Encampment");
    }
    

    //*********************************
    //****** QUERY METHODS ********
    //*********************************

    public double getActionDelay() {
        return robot.getActionDelay();
    }

    public double getHealth() {
        return robot.getEnergonLevel();
    }

    public double getTeamPower() {
        return gameWorld.resources(getTeam());
    }
    
    public double getShields() {
    	return robot.getShieldLevel();
    }

    public MapLocation getLocation() {
        return robot.getLocation();
    }


    public Team getTeam() {
        return robot.getTeam();
    }

    public int getBytecodeLimit() {
        return robot.getBytecodeLimit();
    }

    public RobotType getType() {
        return robot.type;
    }
    
    public int getMapWidth() {
    	return robot.myGameWorld.getGameMap().getWidth();
    }

    public int getMapHeight() {
    	return robot.myGameWorld.getGameMap().getHeight();
    }

    public boolean isConstructing() {
        return getConstructingType() != null;
    }

    public RobotType getConstructingType() {
        return robot.getCapturingType();
    }

    public int getConstructingRounds() {
        return robot.getCapturingRounds();
    }

    //***********************************
    //****** ACTION METHODS *************
    //***********************************

    public void yield() {
        int bytecodesBelowBase = GameConstants.BYTECODE_LIMIT - RobotMonitor.getBytecodesUsed();
        //if (bytecodesBelowBase > 0)
            //gameWorld.adjustResources(getTeam(), GameConstants.POWER_COST_PER_BYTECODE * bytecodesBelowBase);
        RobotMonitor.endRunner();
    }

    public void spawn(Direction dir) throws GameActionException {
    	// only possible for HQ to spawn soldiers
    	RobotType type = RobotType.SOLDIER;
        if (robot.type != RobotType.HQ)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only HQs can spawn.");
        assertNotMoving();
        // check robot limit
        if (gameWorld.countRobots(getTeam()) >= GameConstants.MAX_ROBOTS) {
            throw new GameActionException(CANT_DO_THAT_BRO, "Maximum robot limit reached.");
        }

        MapLocation loc = getLocation().add(dir);
        if (!gameWorld.canMove(type.level, loc))
            throw new GameActionException(GameActionExceptionType.CANT_MOVE_THERE, "That square is occupied.");

        robot.activateMovement(
        		new SpawnSignal(loc, type, robot.getTeam(), robot), 0
        		);
        robot.resetSpawnCounter();
    }
    
    public void construct(RobotType type) throws GameActionException {
    	if (robot.type != RobotType.SOLDIER)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can capture encampments.");
    	if (!type.isBuilding)
            throw new GameActionException(CANT_DO_THAT_BRO, "Must specify a valid encampment type to create");
    	assertNotMoving();
        //assertIsEncampment(getLocation());
        //double cost = GameConstants.CAPTURE_POWER_COST * (gameWorld.getNumCapturing(getTeam()) + gameWorld.getEncampmentsByTeam(getTeam()).size() + 1);
        
        //assertHaveResource(cost);
    	//gameWorld.adjustResources(getTeam(), -cost);
        robot.activateCapturing(new CaptureSignal(getLocation(), type, robot.getTeam(), false, robot), type.captureTurns);
    
    }
    
    public double senseCaptureCost() {
        return 0;
    	//return GameConstants.CAPTURE_POWER_COST * (gameWorld.getNumCapturing(getTeam()) + gameWorld.getEncampmentsByTeam(getTeam()).size() + 1);
    }
    
    public void researchUpgrade(Upgrade upgrade) throws GameActionException {
    	if (robot.type != RobotType.HQ)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only HQs can research.");
    	if (gameWorld.hasUpgrade(getTeam(), upgrade))
    		throw new GameActionException(CANT_DO_THAT_BRO, "You already have that upgrade. ("+upgrade+")");
    	assertNotMoving();
        robot.activateMovement(new ResearchSignal(robot, upgrade), 1);
    }
    
    public int checkResearchProgress(Upgrade upgrade) throws GameActionException {
    	if (robot.type != RobotType.HQ)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only HQs can research.");
    	return gameWorld.getUpgradeProgress(getTeam(), upgrade);
    }
    
    
    public void layMine() throws GameActionException {
    	if (robot.type != RobotType.SOLDIER)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can lay mines.");
    	assertNotMoving();
    	//robot.activateMinelayer(new MinelayerSignal(robot, MineAction.LAYING, getLocation()), GameConstants.MINE_LAY_DELAY);
    }
    
    private void stopMine() throws GameActionException {
    	if (robot.type != RobotType.SOLDIER)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can lay mines.");
    	if (robot.getMiningRounds() == 0)
    		throw new GameActionException(CANT_DO_THAT_BRO, "You are not mining currently");
    	//robot.activateMinelayer(new MinelayerSignal(robot,  MineAction.LAYINGSTOP, getLocation()), GameConstants.MINE_LAY_DELAY);
    }
    
    public int senseMineRoundsLeft() throws GameActionException {
    	if (robot.type != RobotType.SOLDIER)
            throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can lay mines.");
    	return robot.getMiningRounds();
    }
    
    public void defuseMine(MapLocation loc) throws GameActionException {
      
    	// if (robot.type != RobotType.SOLDIER)
      //       throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can lay mines.");
    	// assertNotMoving();
    	
    	// int defuseRadius = 2;
    	// if (hasUpgrade(Upgrade.DEFUSION))
    	// 	defuseRadius = robot.type.sensorRadiusSquared;// + (hasUpgrade(Upgrade.VISION) ? GameConstants.VISION_UPGRADE_BONUS : 0);
    		
    	// if (loc.distanceSquaredTo(getLocation()) > defuseRadius)
    	// 	throw new GameActionException(OUT_OF_RANGE, "You can't defuse that far");
      // /
    	// if (hasUpgrade(Upgrade.DEFUSION))
    	// 	robot.activateDefuser(new MinelayerSignal(robot, MineAction.DEFUSING, loc), GameConstants.MINE_DEFUSE_DEFUSION_DELAY, loc);
    	// else
    	// 	robot.activateDefuser(new MinelayerSignal(robot, MineAction.DEFUSING, loc), GameConstants.MINE_DEFUSE_DELAY, loc);
    }
    
//    public boolean scanMines() throws GameActionException {
//    	if (robot.type != RobotType.SOLDIER)
//            throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can scan for mines.");
//    	assertHaveResource(GameConstants.SCAN_COST);
//        assertHaveUpgrade(Upgrade.MINEDETECTOR);
//        
//        return robot.scanForMines();
//    }
    
    public void resign() {
        for (InternalObject obj : gameWorld.getAllGameObjects())
            if ((obj instanceof InternalRobot) && obj.getTeam() == robot.getTeam())
                gameWorld.notifyDied((InternalRobot) obj);
        gameWorld.removeDead();
    }

    public void selfDestruct() throws GameActionException {
        if (robot.type != RobotType.SOLDIER) {
            throw new GameActionException(GameActionExceptionType.CANT_DO_THAT_BRO, "only soldiers can self destruct");
        }
        robot.setSelfDestruct();
        throw new RobotDeathException();
    }

    public void suicide() {
        throw new RobotDeathException();
    }

    public void breakpoint() {
        gameWorld.notifyBreakpoint();
    }

    //***********************************
    //****** SENSING METHODS *******
    //***********************************

    public double senseTeamMilkQuantity(Team t) {
        if (t == getTeam()) {
            return gameWorld.resources(t);
        } else {
            return ((int) (gameWorld.resources(t) / GameConstants.OPPONENT_MILK_SENSE_ACCURACY)) * GameConstants.OPPONENT_MILK_SENSE_ACCURACY;
        }
    }

    public int senseRobotCount() {
        return gameWorld.countRobots(getTeam());
    }

    public void assertCanSense(MapLocation loc) throws GameActionException {
        if (!checkCanSense(loc))
            throw new GameActionException(CANT_SENSE_THAT, "That location is not within the robot's sensor range.");
    }

    public void assertCanSense(InternalObject obj) throws GameActionException {
        if (!checkCanSense(obj))
            throw new GameActionException(CANT_SENSE_THAT, "That object is not within the robot's sensor range.");
    }

    public boolean checkCanSense(MapLocation loc) {
    	
      int sensorRadius = robot.type.sensorRadiusSquared; // + (hasUpgrade(Upgrade.VISION) ? GameConstants.VISION_UPGRADE_BONUS : 0);
//    	return getGameObjectsNearLocation(Robot.class, loc, sensorRadius, robot.getTeam()).length > 0;
    	
      if (robot.myLocation.distanceSquaredTo(loc) <= sensorRadius)
        	return true;
       
        /* 
        for (InternalObject o : gameWorld.allObjects())
        {
        	if  ((Robot.class.isInstance(o)) 
        			&& o.getTeam() == robot.getTeam()
        			&& loc.distanceSquaredTo(o.getLocation()) <= sensorRadius)
        		return true;
        		
        }
        */
        return false;
    	// make global vision work on this.
    	// MAKE SURE YOU CANT GLOBL DEFUSE SHIT OTHERWISE YOURE GUNNA GET G'D
//        MapLocation myLoc = getLocation();
//        int sensorRadius = robot.type.sensorRadiusSquared + (hasUpgrade(Upgrade.VISION) ? GameConstants.VISION_UPGRADE_BONUS : 0);
//        return myLoc.distanceSquaredTo(loc) <= sensorRadius;
////                && gameWorld.inAngleRange(myLoc, getDirection(), loc, robot.type.sensorCosHalfTheta);
    }
    
    public boolean checkCanSense(InternalObject obj) {
        return obj.exists() && (obj.getTeam() == getTeam() || checkCanSense(obj.getLocation()));
    }

    public GameObject senseObjectAtLocation(MapLocation loc) throws GameActionException {
        assertNotNull(loc);
        assertCanSense(loc);
        return gameWorld.getObject(loc, RobotLevel.ON_GROUND);
    }

    @SuppressWarnings("unchecked")
    public <T extends GameObject> T[] senseNearbyGameObjects(final Class<T> type) {
        Predicate<InternalObject> p = new Predicate<InternalObject>() {
            public boolean apply(InternalObject o) {
                return checkCanSense(o) && (type.isInstance(o)) && (!o.equals(robot));
            }
        };
        return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    }
    
    @SuppressWarnings("unchecked")
	public <T extends GameObject> T[] senseNearbyGameObjects(final Class<T> type, final int radiusSquared) {
        Predicate<InternalObject> p = new Predicate<InternalObject>() {
            public boolean apply(InternalObject o) {
                return o.myLocation.distanceSquaredTo(robot.myLocation) <= radiusSquared 
                		&& checkCanSense(o) && (type.isInstance(o)) && (!o.equals(robot));
            }
        };
        return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    }

    @SuppressWarnings("unchecked")
	public <T extends GameObject> T[] senseNearbyGameObjects(final Class<T> type, final int radiusSquared, final Team team) {
    	if (team == null)
    		return senseNearbyGameObjects(type, radiusSquared);
        Predicate<InternalObject> p = new Predicate<InternalObject>() {
            public boolean apply(InternalObject o) {
                return o.myLocation.distanceSquaredTo(robot.myLocation) <= radiusSquared
                		&& o.getTeam() == team
                		&& checkCanSense(o) && (type.isInstance(o)) && (!o.equals(robot));
            }
        };
        return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    }

    @SuppressWarnings("unchecked")
    public <T extends GameObject> T[] senseNearbyGameObjects(final Class<T> type, final MapLocation center, final int radiusSquared, final Team team) {
    	if (team == null)
    	{
    		Predicate<InternalObject> p = new Predicate<InternalObject>() {
                public boolean apply(InternalObject o) {
                    return o.myLocation.distanceSquaredTo(center) <= radiusSquared
                    		&& checkCanSense(o) && (type.isInstance(o)) && (!o.equals(robot));
                }
            };
            return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    	}
        Predicate<InternalObject> p = new Predicate<InternalObject>() {
            public boolean apply(InternalObject o) {
                return o.myLocation.distanceSquaredTo(center) <= radiusSquared
                		&& o.getTeam() == team
                		&& checkCanSense(o) && (type.isInstance(o)) && (!o.equals(robot));
            }
        };
        return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    }

    public Robot[] senseBroadcastingRobots() {
        return robot.myGameWorld.getRevealedRobots().toArray(new Robot[0]);
    }

    public Robot[] senseBroadcastingRobots(final Team t) {
        Predicate<Robot> p = new Predicate<Robot>() {
            public boolean apply(Robot r) {
                return r.getTeam() == t;
            }
        };
        return Iterables.toArray(Iterables.filter(robot.myGameWorld.getRevealedRobots(), p), Robot.class);
    }

    public MapLocation[] senseBroadcastingRobotLocations() {
        InternalRobot[] bots = robot.myGameWorld.getRevealedRobots().toArray(new InternalRobot[0]);
        MapLocation[] locs = new MapLocation[bots.length];
        for (int i = 0; i < bots.length; i++) {
            locs[i] = bots[i].getLocation();
        }
        return locs;
    }

    public MapLocation[] senseBroadcastingRobotLocations(final Team t) {
        Predicate<Robot> p = new Predicate<Robot>() {
            public boolean apply(Robot r) {
                return r.getTeam() == t;
            }
        };
        InternalRobot[] bots = Iterables.toArray(Iterables.filter(robot.myGameWorld.getRevealedRobots(), p), InternalRobot.class);
        MapLocation[] locs = new MapLocation[bots.length];
        for (int i = 0; i < bots.length; i++) {
            locs[i] = bots[i].getLocation();
        }
        return locs;
    }

    
    /**
     * Private version used for engine checks to see if there is a robot w/ a given characteristic
     */
    @SuppressWarnings("unchecked")
	private <T extends GameObject> T[] getGameObjectsNearLocation (final Class<T> type, final MapLocation location, final int radiusSquared, final Team team) {
        Predicate<InternalObject> p = new Predicate<InternalObject>() {
            public boolean apply(InternalObject o) {
                return (type.isInstance(o)) &&
                		o.getTeam() == team &&
                		location.distanceSquaredTo(o.getLocation()) <= radiusSquared;
            }
        };
        return Iterables.toArray((Iterable<T>) Iterables.filter(gameWorld.allObjects(), p), type);
    }
    
    public RobotInfo senseRobotInfo(Robot r) throws GameActionException {
        InternalRobot ir = castInternalRobot(r);
        assertCanSense(ir);
        return new RobotInfo(ir, ir.sensedLocation(), ir.getEnergonLevel(),
                ir.getDirection(), ir.type, ir.getTeam(), ir.getActionDelay(),
                ir.getCapturingType() != null, ir.getCapturingType(), ir.getCapturingRounds());
    }
    
    public boolean senseEnemyNukeHalfDone() throws GameActionException {
    	if (getRobot().type != RobotType.HQ)
    		throw new GameActionException(CANT_DO_THAT_BRO, "Only HQs can sense NUKE progress");
    	return gameWorld.getUpgradeProgress(getTeam().opponent(), Upgrade.NUKE) >= Upgrade.NUKE.numRounds/2;
    }

    public MapLocation senseLocationOf(GameObject o) throws GameActionException {
        InternalObject io = castInternalObject(o);
        assertCanSense(io);
        return io.sensedLocation();
    }

    public boolean canSenseObject(GameObject o) {
        return checkCanSense(castInternalObject(o));
    }

    public boolean canSenseSquare(MapLocation loc) {
        return checkCanSense(loc);
    }

    public MapLocation senseHQLocation() {
        return gameWorld.getBaseHQ(getTeam()).getLocation();
    }
    
    public MapLocation senseEnemyHQLocation() {
    	return gameWorld.getBaseHQ(getTeam().opponent()).getLocation();
    }

    public TerrainTile senseTerrainTile(MapLocation loc) {
        assertNotNull(loc);
        return gameWorld.getMapTerrain(loc);
    }
    
    public MapLocation[] senseAllEncampmentSquares() {
    	return gameWorld.getAllEncampments().toArray(new MapLocation[0]);
    }
    
    public MapLocation[] senseAlliedEncampmentSquares() {
    	return gameWorld.getEncampmentsByTeam(getTeam()).toArray(new MapLocation[]{});
    }
    
    public MapLocation[] senseEncampmentSquares(final MapLocation center, final int radiusSquared, final Team team) throws GameActionException {
    	if (team == getTeam().opponent())
    		throw new GameActionException(CANT_DO_THAT_BRO, "can't sense enemy encampments");
    	ArrayList<MapLocation> camps = new ArrayList<MapLocation>();
    	Map<MapLocation, Team> campmap = gameWorld.getEncampmentMap();
    	for (Entry<MapLocation, Team> entry : campmap.entrySet())
    	{
    		if ( ( team == null 
    				|| (team == getTeam() && entry.getValue() == team)
    				|| (team == Team.NEUTRAL && entry.getValue() != getTeam()) )
    				&& center.distanceSquaredTo(entry.getKey()) <= radiusSquared)
    			camps.add(entry.getKey());
    	}
        return camps.toArray(new MapLocation[]{});
    }
    
    public Team senseMine(MapLocation loc) {
    	Team mt = gameWorld.getMine(loc);
    	if((mt == getTeam().opponent()) && !(gameWorld.isKnownMineLocation(getTeam(), loc)))
    		return null;
    	return mt;
    }
    
    public boolean senseEncampmentSquare(MapLocation loc) {
    	return gameWorld.getEncampment(loc) != null;
    }
    
//    public int senseAlliedMines(MapLocation loc) {
//    	return gameWorld.getMineCount(getTeam(), loc);
//    }
//    
//    public int senseEnemyMines(MapLocation loc) {
//    	return gameWorld.isKnownMineLocation(getTeam(), loc) ? gameWorld.getMineCount(getTeam().opponent(), loc) : 0;
//    }
    
    public MapLocation[] senseMineLocations(final MapLocation center, final int radiusSquared, final Team team) {
    	final Set<MapLocation> knownlocs = gameWorld.getKnownMineMap(getTeam());
    	if (team == null)
    	{
    		if (radiusSquared >= GameConstants.MAP_MAX_HEIGHT*GameConstants.MAP_MAX_HEIGHT + GameConstants.MAP_MAX_WIDTH*GameConstants.MAP_MAX_WIDTH)
        	{
    			Predicate<MapLocation> p = new Predicate<MapLocation>() {
            		public boolean apply(MapLocation o) {
            			return gameWorld.getMine(o) != getTeam().opponent()
            					|| knownlocs.contains(o);
            		}
            	};
            	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
        	}
    		Predicate<MapLocation> p = new Predicate<MapLocation>() {
        		public boolean apply(MapLocation o) {
        			return (gameWorld.getMine(o) != getTeam().opponent() || knownlocs.contains(o))
        					&& center.distanceSquaredTo(o) <= radiusSquared;
        		}
        	};
        	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    	}
    	if (radiusSquared >= GameConstants.MAP_MAX_HEIGHT*GameConstants.MAP_MAX_HEIGHT + GameConstants.MAP_MAX_WIDTH*GameConstants.MAP_MAX_WIDTH)
    	{
    		if (team != getTeam().opponent())
    		{
    			Predicate<MapLocation> p = new Predicate<MapLocation>() {
            		public boolean apply(MapLocation o) {
            			return gameWorld.getMine(o) == team;
            		}
            	};
            	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    		}
    		Predicate<MapLocation> p = new Predicate<MapLocation>() {
        		public boolean apply(MapLocation o) {
        			return gameWorld.getMine(o) == team
        					&& knownlocs.contains(o);
        		}
        	};
        	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    	}
    	if (team != getTeam().opponent())
		{
        	Predicate<MapLocation> p = new Predicate<MapLocation>() {
        		public boolean apply(MapLocation o) {
        			return center.distanceSquaredTo(o) <= radiusSquared
    		        		 && gameWorld.getMine(o) == team;
        		}
        	};
        	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
		}
    	Predicate<MapLocation> p = new Predicate<MapLocation>() {
    		public boolean apply(MapLocation o) {
    			return center.distanceSquaredTo(o) <= radiusSquared
		        		 && gameWorld.getMine(o) == team
		        		 && knownlocs.contains(o);
    		}
    	};
    	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    }
    
    public MapLocation[] senseNonAlliedMineLocations(final MapLocation center, final int radiusSquared) {
    	final Set<MapLocation> knownlocs = gameWorld.getKnownMineMap(getTeam());
    	if (radiusSquared >= GameConstants.MAP_MAX_HEIGHT*GameConstants.MAP_MAX_HEIGHT + GameConstants.MAP_MAX_WIDTH*GameConstants.MAP_MAX_WIDTH)
    	{
    		Predicate<MapLocation> p = new Predicate<MapLocation>() {
        		public boolean apply(MapLocation o) {
        			return (gameWorld.getMine(o) != getTeam()
        					&& knownlocs.contains(o))
        					|| gameWorld.getMine(o) == Team.NEUTRAL;
        					
        		}
        	};
        	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    	}
    	Predicate<MapLocation> p = new Predicate<MapLocation>() {
    		public boolean apply(MapLocation o) {
    			return center.distanceSquaredTo(o) <= radiusSquared
		        		 && ((gameWorld.getMine(o) != getTeam()
		        		 && knownlocs.contains(o))
		        		 || gameWorld.getMine(o) == Team.NEUTRAL);
    		}
    	};
    	return Iterables.toArray((Iterable<MapLocation>) Iterables.filter(gameWorld.getMineMaps().keySet(), p), MapLocation.class); 
    }

    // TODO(axc): write this more cleanly
    public MapLocation[] sensePastrLocations(final Team t) {
        ArrayList<MapLocation> res = new ArrayList<MapLocation>();
        for (InternalObject obj : gameWorld.allObjects()) {
            InternalRobot ir = (InternalRobot) obj;
            if (obj.getTeam() == t && ir.type == RobotType.PASTR) {
                res.add(ir.getLocation());
            }
        }
        return res.toArray(new MapLocation[0]);
    }
   

    public double[][] senseCowGrowth() {
        return gameWorld.getCowsCopy();
    }

    public double senseCowsAtLocation(MapLocation m) throws GameActionException {
        assertCanSense(m);
        return gameWorld.getGameMap().getNeutralsMap().get(m);
    }

    public void assertNoActionDelay() throws GameActionException {
        if (robot.getActionDelay() >= 1.0) {
            throw new GameActionException(GameActionExceptionType.CANT_DO_THAT_BRO, "You have action delay and cannot do that");
        }
    }

    // ***********************************
    // ****** MOVEMENT METHODS ********
    // ***********************************

    public int roundsUntilActive() {
        int spawnRounds = 0;
        if (robot.type == RobotType.HQ) {
            gameWorld.adjustSpawnRate(getTeam());
            spawnRounds = robot.roundsUntilCanSpawn(gameWorld.getSpawnRate(getTeam()));
        }
        return Math.max((int) robot.getActionDelay(), spawnRounds);
    }

    public boolean isActive() {
        boolean canSpawn = true;
        if (robot.type == RobotType.HQ) {
            gameWorld.adjustSpawnRate(getTeam());
            canSpawn = robot.canSpawn(gameWorld.getSpawnRate(getTeam()));
        }
        return robot.getActionDelay() < 1.0 && canSpawn;
    }

    public void assertNotMoving() throws GameActionException {
        if (!isActive())
            throw new GameActionException(NOT_ACTIVE, "This robot has action delay and cannot move.");
    }

    public void move(Direction d) throws GameActionException {
        if (robot.type != RobotType.SOLDIER)
        	throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can move.");
    	assertNotMoving();
        assertCanMove(d);
        assertNoActionDelay();
        double delay = robot.calculateMovementActionDelay(getLocation(), getLocation().add(d), senseTerrainTile(getLocation()), MovementType.RUN);
        robot.activateMovement(new MovementSignal(robot, getLocation().add(d),
                true, (int) delay, MovementType.RUN), delay);
    }

    public void sneak(Direction d) throws GameActionException {
        if (robot.type != RobotType.SOLDIER)
        	throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERs can move.");
    	assertNotMoving();
        assertCanMove(d);
        double delay = robot.calculateMovementActionDelay(getLocation(), getLocation().add(d), senseTerrainTile(getLocation()), MovementType.SNEAK);
        robot.activateMovement(new MovementSignal(robot, getLocation().add(d),
                true, (int) delay, MovementType.SNEAK), delay);
    }

    public boolean canMove(Direction d) {
        if (d == Direction.NONE || d == Direction.OMNI)
            return false;
        assertValidDirection(d);
        return gameWorld.canMove(robot.getRobotLevel(), getLocation().add(d));
    }

    public void assertCanMove(Direction d) throws GameActionException {
        if (!canMove(d))
            throw new GameActionException(GameActionExceptionType.CANT_MOVE_THERE, "Cannot move in the given direction: " + d);
    }

    protected void assertValidDirection(Direction d) {
        assertNotNull(d);
        if (d == Direction.NONE || d == Direction.OMNI)
            throw new IllegalArgumentException("You cannot move in the direction NONE or OMNI.");
    }

    // ***********************************
    // ****** ATTACK METHODS ********
    // ***********************************

    public boolean isAttackActive() {
        return !isActive();
    }

    protected void assertNotAttacking() throws GameActionException {
        if (isAttackActive())
            throw new GameActionException(NOT_ACTIVE, "This robot has action delay and cannot attack.");
    }

    protected void assertCanAttack(MapLocation loc, RobotLevel height) throws GameActionException {
        if (!canAttackSquare(loc))
            throw new GameActionException(OUT_OF_RANGE, "That location is out of this robot's attack range");
    }

    public boolean canAttackSquare(MapLocation loc) {
        assertNotNull(loc);
        return GameWorld.canAttackSquare(robot, loc);
    }
    
//    public void attack() throws GameActionException {
//        if (robot.type != RobotType.SOLDIER)
//        	throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIER make melee attacks.");
//    	assertNotAttacking();
//    	
//    	robot.activateAttack(new AttackSignal(robot, robot.getLocation(), RobotLevel.ON_GROUND), robot.type.attackDelay);
//    }

    public void attackSquare(MapLocation loc) throws GameActionException {
        assertNotAttacking();
        assertNotMoving();
        assertNotNull(loc);
        assertCanAttack(loc, RobotLevel.ON_GROUND);
        assertNoActionDelay();
        robot.activateAttack(new AttackSignal(robot, loc, RobotLevel.ON_GROUND), robot.calculateAttackActionDelay(robot.type));
    }

    public void attackSquareLight(MapLocation loc) throws GameActionException {
        if (getType() != RobotType.NOISETOWER) {
            throw new GameActionException(GameActionExceptionType.CANT_DO_THAT_BRO, "Only Noise Towers can use a light attack");
        }
        assertNotAttacking();
        assertNotMoving();
        assertNotNull(loc);
        assertCanAttack(loc, RobotLevel.ON_GROUND);
        assertNoActionDelay();
        robot.activateAttack(new AttackSignal(robot, loc, RobotLevel.ON_GROUND, 1), robot.calculateAttackActionDelay(robot.type));
    }

    //************************************
    //******** BROADCAST METHODS **********
    //************************************

    public boolean hasBroadcasted() {
        return robot.hasBroadcasted();
    }
    
    public void broadcast(int channel, int data) throws GameActionException {
    	if (channel<0 || channel>GameConstants.BROADCAST_MAX_CHANNELS)
    		throw new GameActionException(CANT_DO_THAT_BRO, "Can only use radio channels from 0 to "+GameConstants.BROADCAST_MAX_CHANNELS+", inclusive");
    	
    	robot.addBroadcast(channel, data);
    	//gameWorld.adjustResources(getTeam(), -cost);
    	
    }
    
    @Override
    public int readBroadcast(int channel) throws GameActionException {
    	if (channel<0 || channel>GameConstants.BROADCAST_MAX_CHANNELS)
    		throw new GameActionException(CANT_DO_THAT_BRO, "Can only use radio channels from 0 to "+GameConstants.BROADCAST_MAX_CHANNELS+", inclusive");
    	int m = gameWorld.getMessage(robot.getTeam(), channel);
    	//gameWorld.adjustResources(getTeam(), -cost);
    	return m;
    }

    //************************************
    //******** MISC. METHODS **********
    //************************************
    
    public void wearHat() throws GameActionException {
    	if (getType() != RobotType.SOLDIER)
    		throw new GameActionException(CANT_DO_THAT_BRO, "Only SOLDIERS can wear hats.");
    	assertHaveResource(GameConstants.HAT_MILK_COST);
    	assertNotMoving();
    	gameWorld.adjustResources(getTeam(), -GameConstants.HAT_MILK_COST);
    	robot.activateMovement(new HatSignal(robot, gameWorld.randGen.nextInt()), 1);
    }
   
    public boolean hasUpgrade(Upgrade upgrade) {
    	assertNotNull(upgrade);
    	return gameWorld.hasUpgrade(getTeam(), upgrade);
    }
    
    public void setIndicatorString(int stringIndex, String newString) {
        if (stringIndex >= 0 && stringIndex < GameConstants.NUMBER_OF_INDICATOR_STRINGS)
            (new IndicatorStringSignal(robot, stringIndex, newString)).accept(gameWorld);
    }

    public void setIndicatorStringFormat(int stringIndex, String format, Object... args) {
        setIndicatorString(stringIndex, String.format(format, args));
    }

    public long getControlBits() {
        return robot.getControlBits();
    }

    public void addMatchObservation(String observation) {
        (new MatchObservationSignal(robot, observation)).accept(gameWorld);
    }

    public void setTeamMemory(int index, long value) {
        gameWorld.setArchonMemory(robot.getTeam(), index, value);
    }

    public void setTeamMemory(int index, long value, long mask) {
        gameWorld.setArchonMemory(robot.getTeam(), index, value, mask);
    }

    public long[] getTeamMemory() {
        long[] arr = gameWorld.getOldArchonMemory()[robot.getTeam().ordinal()];
        return Arrays.copyOf(arr, arr.length);
    }

    public int hashCode() {
        return robot.getID();
    }

}
