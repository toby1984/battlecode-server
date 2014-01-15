package battlecode.engine;

import battlecode.engine.instrumenter.RobotMonitor;
import battlecode.engine.instrumenter.RobotMonitor.RobotData;
import battlecode.engine.signal.SignalHandler;

/**
 * An interface that includes the part of the game world that isn't expected to change
 * from year to year.
 */
public interface GenericWorld extends GameWorldViewer, SignalHandler {

    public void beginningOfExecution(int id);

    public void endOfExecution(RobotMonitor.RobotData data);

    public void processBeginningOfRound();

    public void processEndOfRound();

    public long[][] getArchonMemory();

    public void resetStatic();

    public void clearAllSignals();

    public boolean wasBreakpointHit();

    public GenericRobot getRobotByID(int id);

    public int nextID();

	public void robotThreadDied(RobotData data);
}
