package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransferStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.TransitStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.BitSetIterator;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;


/**
 * Tracks the state of a RAPTOR search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p/>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * want the Algorithm to be as clean as possible and to be able to swap the state implementation - try out and
 * experiment with different state implementations.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class McRangeRaptorWorkerState<T extends TripScheduleInfo> implements WorkerState<T> {

    private final Stops<T> stops;
    private final List<AbstractStopArrival<T>> arrivalsCache = new ArrayList<>();
    private final int nRounds;
    private final CostCalculator costCalculator;
    private final TransitCalculator transitCalculator;

    private int round = Integer.MIN_VALUE;
    private boolean updatesExist = false;
    private BitSet touchedStops;

    /**
     * create a RaptorState for a network with a particular number of stops, and a given maximum duration
     */
    McRangeRaptorWorkerState(
            int nRounds,
            int nStops,
            Collection<TransferLeg> egressLegs,
            CostCalculator costCalculator,
            TransitCalculator transitCalculator,
            DebugHandlerFactory<T> debugHandlerFactory

    ) {
        this.nRounds = nRounds;
        this.stops = new Stops<>(nStops, egressLegs, costCalculator, transitCalculator, debugHandlerFactory);
        this.touchedStops = new BitSet(nStops);
        this.costCalculator = costCalculator;
        this.transitCalculator = transitCalculator;
    }

    @Override
    public void setupIteration(int iterationDepartureTime) {
        round = 0;
        arrivalsCache.clear();
        stops.startNewIteration(iterationDepartureTime);

        // clear all touched stops to avoid constant rexploration
        startRecordChangesToStopsForNextAndCurrentRound();
    }

    @Override
    public void setInitialTimeForIteration(TransferLeg accessLeg, int iterationDepartureTime) {
        int cost = costCalculator.walkCost(accessLeg.durationInSeconds());
        stops.setInitialTime(iterationDepartureTime, accessLeg, cost);
        touchedStops.set(accessLeg.stop());
        updatesExist = true;
    }

    @Override
    public boolean isNewRoundAvailable() {
        final boolean moreRoundsToGo = round < nRounds - 1;
        return moreRoundsToGo && updatesExist;
    }

    @Override
    public void prepareForNextRound() {
        ++round;
    }

    @Override
    public IntIterator stopsTouchedPreviousRound() {
        return new BitSetIterator(touchedStops);
    }

    @Override
    public IntIterator stopsTouchedByTransitCurrentRound() {
        return new BitSetIterator(touchedStops);
    }

    Iterable<? extends AbstractStopArrival<T>> listStopArrivalsPreviousRound(int stop) {
        return stops.listArrivalsAfterMark(stop);
    }

    /**
     * Set the time at a transit stop iff it is optimal.
     */
    void transitToStop(AbstractStopArrival<T> previousStopArrival, int stop, int alightTime, int boardTime, T trip) {
        if (exceedsTimeLimit(alightTime)) {
            return;
        }
        int cost = costCalculator.transitArrivalCost(previousStopArrival.arrivalTime(), boardTime, alightTime);
        arrivalsCache.add(new TransitStopArrival<>(previousStopArrival, stop, alightTime, boardTime, trip, cost));
    }

    /**
     * Set the time at a transit stops iff it is optimal.
     */
    @Override
    public void transferToStops(int fromStop, Iterator<? extends TransferLeg> transfers) {
        Iterable<? extends AbstractStopArrival<T>> fromArrivals = stops.listArrivalsAfterMark(fromStop);

        while (transfers.hasNext()) {
            transferToStop(fromArrivals, transfers.next());
        }
    }

    @Override
    public void transitsForRoundComplete() {
        startRecordChangesToStopsForNextAndCurrentRound();
        commitCachedArrivals(TransitStopArrival.class);
    }

    @Override
    public void transfersForRoundComplete() {
        commitCachedArrivals(TransferStopArrival.class);
    }

    @Override
    public Collection<Path<T>> extractPaths() {
        return stops.extractPaths();
    }


    /* private methods */

    private void startRecordChangesToStopsForNextAndCurrentRound() {
        stops.markAllStops();
        touchedStops.clear();
        updatesExist = !arrivalsCache.isEmpty();
    }


    private void transferToStop(Iterable<? extends AbstractStopArrival<T>> fromArrivals, TransferLeg transfer) {
        final int transferTimeInSeconds = transfer.durationInSeconds();

        for (AbstractStopArrival<T> it : fromArrivals) {
            int arrivalTime = it.arrivalTime() + transferTimeInSeconds;

            if (!exceedsTimeLimit(arrivalTime)) {
                int cost = costCalculator.walkCost(transferTimeInSeconds);
                arrivalsCache.add(new TransferStopArrival<>(it, transfer, arrivalTime, cost));
            }
        }
    }

    private void commitCachedArrivals(Class<? extends AbstractStopArrival> type) {
        for (AbstractStopArrival<T> arrival : arrivalsCache) {
            if (stops.addStopArrival(arrival)) {
                touchedStops.set(arrival.stop());
            }
        }
        arrivalsCache.clear();
    }

    private boolean exceedsTimeLimit(int time) {
        return transitCalculator.exceedsTimeLimit(time);
    }
}
