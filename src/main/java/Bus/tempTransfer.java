package Bus;

import Database.DatabaseConnection;
import java.sql.*;

import Calculators.*;

public class tempTransfer {

    public static TripInfo processTransfers(double x1, double y1, double x2, double y2) throws SQLException { // Initialize
                                                                                                              // database
        // connection
        try (Connection con = DatabaseConnection.getConnection()) {
            /*
             * from 6227 XB to 6125 RB
             * double x1 = 50.8391159;
             * double y1 = 5.7342817;
             * 
             * // Coordinates of the end point
             * double x2 = 50.8384691;
             * double y2 = 5.6469823;
             */

            /*
             * from 6227 XB to 6223BJ
             * double x1 = 50.8391159;
             * double y1 = 5.7342817;
             * 
             * // Coordinates of the end point
             * double x2 = 50.877973;
             * double y2 = 5.687432;
             */

            /*
             * from 6213NE to 6222nk
             * double x1 = 50.829421;
             * double y1 = 5.663643;
             * 
             * // Coordinates of the end point
             * double x2 = 50.8777704434;
             * double y2 = 5.722604;
             */

            setupNearestStops(con, x1, y1, x2, y2);
            createRouteTransferTable(con);
            createFinalRouteTransferTable(con);

            // Step 1: Read all data from finalRouteTransfer
            String fetchFinalRouteTransfer = """
                    SELECT
                        start_route_id,
                        start_stop_id,
                        end_route_id,
                        end_stop_id,
                        stop_1_id,
                        stop_2_id,
                        start_stop_lat,
                        start_stop_lon,
                        end_stop_lat,
                        end_stop_lon
                    FROM
                        finalRouteTransfer;
                    """;

            try (Statement stmt = con.createStatement();
                    Statement stmt1 = con.createStatement();
                    ResultSet rs = stmt.executeQuery(fetchFinalRouteTransfer)) {

                // Create the tempTransfer table
                stmt1.executeUpdate("TRUNCATE table tempTransfer; ");
                while (rs.next()) {
                    String startRouteId = rs.getString("start_route_id");
                    String startStopId = rs.getString("start_stop_id");
                    String endRouteId = rs.getString("end_route_id");
                    String endStopId = rs.getString("end_stop_id");
                    String stop1ID = rs.getString("stop_1_id");
                    String stop2ID = rs.getString("stop_2_id");
                    double startStopLat = rs.getDouble("start_stop_lat");
                    double startStopLon = rs.getDouble("start_stop_lon");
                    double endStopLat = rs.getDouble("end_stop_lat");
                    double endStopLon = rs.getDouble("end_stop_lon");

                    Time currentTime = TimeCalculator.getCurrentTime();// Base time

                    // Step 2: Process each row to find the trips
                    String firstTripQuery = getFirstTripQuery();
                    TripInfo firstTrip = fetchTripDetails(con, firstTripQuery, startStopId, stop1ID,
                            startRouteId, currentTime);

                    if (firstTrip != null) {
                        double distanceToStartBusstop = TimeCalculator.calculateDistanceIfNotCached(endStopLat,
                                endStopLon, x2, y2);

                        Time newTime = TimeCalculator.calculateTime(x1, y1, startStopLat,
                                startStopLon);
                        firstTrip = fetchTripDetails(con, firstTripQuery, startStopId, stop1ID,
                                startRouteId, newTime);

                        String secondTripQuery = getSecondTripQuery();
                        TripInfo secondTrip = fetchTripDetails(con, secondTripQuery, stop2ID, endStopId,
                                endRouteId, firstTrip.getEndArrivalTime());

                        // Step 3: Insert the result of secondTrip into tempTransfer
                        if (secondTrip != null) {

                            double distanceToDest = TimeCalculator.calculateDistanceIfNotCached(endStopLat, endStopLon,
                                    x2, y2);

                            TimeCalculator timeCalc = new AverageTimeCalculator(distanceToDest);
                            int timeToDestination = (int) (Math.round(timeCalc.getWalkingTime()));
                            // int timeToDestination = 0;
                            insertIntoTempTransfer(con, firstTrip, secondTrip, timeToDestination,
                                    distanceToStartBusstop);

                        }
                    }

                }

            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return getTransferBestTrip();
    }

    public static TripInfo getFirstTrip() throws SQLException {
        Connection conn = DatabaseConnection.getConnection();
        TripInfo transferBestTrip = null;
        String sqlGetEarliestArrTime = """
                SELECT tt.*
                FROM tempTransfer tt
                ORDER BY second_arrival_time ASC,distanceToFirstBusstop ASC
                LIMIT 1;
                                                               """;
        Statement stmtGetBesttrip = conn.createStatement();

        ResultSet rs = stmtGetBesttrip.executeQuery(sqlGetEarliestArrTime);
        if (rs.next()) {
            transferBestTrip = new TripInfo(
                    rs.getString("first_route_id"),
                    rs.getString("first_route_short_name"),
                    rs.getString("first_trip_id"),
                    rs.getString("first_start_bus_stop_id"),
                    rs.getString("first_end_bus_stop_id"),
                    rs.getString("first_departure_time"),
                    rs.getString("first_arrival_time"),
                    rs.getInt("first_trip_time"));
        }
        return transferBestTrip;

    }

    public static TripInfo getTransferBestTrip() throws SQLException {
        Connection conn = DatabaseConnection.getConnection();
        TripInfo transferBestTrip = null;
        String sqlGetEarliestArrTime = """
                SELECT tt.*
                FROM tempTransfer tt
                ORDER BY second_arrival_time ASC,distanceToFirstBusstop ASC
                LIMIT 1;
                                                               """;
        Statement stmtGetBesttrip = conn.createStatement();

        ResultSet rs = stmtGetBesttrip.executeQuery(sqlGetEarliestArrTime);
        if (rs.next()) {
            transferBestTrip = new TripInfo(
                    rs.getString("second_route_id"),
                    rs.getString("second_route_short_name"),
                    rs.getString("second_trip_id"),
                    rs.getString("second_start_bus_stop_id"),
                    rs.getString("second_end_bus_stop_id"),
                    rs.getString("second_departure_time"),
                    rs.getString("second_arrival_time"),
                    rs.getInt("second_trip_time"));
        }
        return transferBestTrip;

    }

    public static void setupNearestStops(Connection conn, double startLat, double startLon, double endLat,
            double endLon) throws SQLException {
        String sqlDropStartStops = "DROP TABLE IF EXISTS nearest_start_stops;";
        String createNearestStartStops = """
                CREATE TEMPORARY TABLE nearest_start_stops AS
                SELECT stop_id, stop_name, ST_Distance_Sphere(point(?, ?), point(stops.stop_lon, stops.stop_lat)) AS distance
                FROM stops ORDER BY distance LIMIT 20;""";
        String sqlDropEndStops = "DROP TABLE IF EXISTS nearest_end_stops;";
        String createNearestEndStops = """
                CREATE TEMPORARY TABLE nearest_end_stops AS
                SELECT stop_id, stop_name, ST_Distance_Sphere(point(?, ?), point(stops.stop_lon, stops.stop_lat)) AS distance
                FROM stops ORDER BY distance LIMIT 20;""";

        try (PreparedStatement pstmt1 = conn.prepareStatement(createNearestStartStops);
                PreparedStatement pstmt2 = conn.prepareStatement(createNearestEndStops)) {
            Statement stmt1 = conn.createStatement();
            stmt1.execute(sqlDropStartStops);
            Statement stmt2 = conn.createStatement();
            stmt2.execute(sqlDropEndStops);

            pstmt1.setDouble(1, startLon);
            pstmt1.setDouble(2, startLat);
            pstmt2.setDouble(1, endLon);
            pstmt2.setDouble(2, endLat);
            pstmt1.executeUpdate();
            pstmt2.executeUpdate();
        }
    }

    private static void createRouteTransferTable(Connection conn) throws SQLException {
        String createTableQuery = """
                CREATE TEMPORARY TABLE routeTransfer AS
                WITH StartRoutes AS (
                    SELECT
                        DISTINCT r.route_id, nss.stop_id
                    FROM
                        nearest_start_stops nss
                    JOIN
                        stop_times st ON nss.stop_id = st.stop_id
                    JOIN
                        trips t ON st.trip_id = t.trip_id
                    JOIN
                        routes r ON t.route_id = r.route_id
                ),
                EndRoutes AS (
                    SELECT
                        DISTINCT r.route_id, nes.stop_id
                    FROM
                        nearest_end_stops nes
                    JOIN
                        stop_times st ON nes.stop_id = st.stop_id
                    JOIN
                        trips t ON st.trip_id = t.trip_id
                    JOIN
                        routes r ON t.route_id = r.route_id
                )
                SELECT
                    sr.route_id AS start_route_id,
                    sr.stop_id As start_stop_id,
                    er.route_id AS end_route_id,
                    er.stop_id As end_stop_id
                FROM
                    StartRoutes sr
                JOIN
                    EndRoutes er;
                    """;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTableQuery);
            System.out.println("Temporary table routeTransfer created successfully.");
        }
    }

    private static void createFinalRouteTransferTable(Connection conn) throws SQLException {
        String createTableQuery = """
                CREATE TEMPORARY TABLE finalRouteTransfer AS
                SELECT DISTINCT
                    rt.*,
                    ats.stop_1_id,
                    ats.stop_2_id,
                    s1.stop_lat AS start_stop_lat,
                    s1.stop_lon AS start_stop_lon,
                    s2.stop_lat AS end_stop_lat,
                    s2.stop_lon AS end_stop_lon
                FROM
                    routeTransfer rt
                JOIN
                    AllTransferStops ats ON rt.start_route_id = ats.route_id_1 AND rt.end_route_id = ats.route_id_2
                JOIN
                    stops s1 ON rt.start_stop_id = s1.stop_id
                JOIN
                    stops s2 ON rt.end_stop_id = s2.stop_id;
                                                                    """;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTableQuery);
            System.out.println("Temporary table finalRouteTransfer created successfully.");
        }
    }

    private static String getFirstTripQuery() {
        return """
                    SELECT
                    start_stop_id,
                    end_stop_id,
                    route_id,
                    route_short_name,
                    route_long_name,
                    trip_id,
                    start_departure_time,
                    end_arrival_time,
                    TIMESTAMPDIFF(MINUTE, start_departure_time, end_arrival_time) AS trip_time
                FROM
                    preComputedTripDetails
                WHERE
                    start_stop_id = ?
                    AND end_stop_id = ?
                    AND route_id = ?
                    AND start_departure_time >= ?
                ORDER BY
                    start_departure_time ASC
                LIMIT 1;
                        """;
    }

    private static String getSecondTripQuery() {

        return """
                    SELECT
                    start_stop_id,
                    end_stop_id,
                    route_id,
                    route_short_name,
                    route_long_name,
                    trip_id,
                    start_departure_time,
                    end_arrival_time,
                    TIMESTAMPDIFF(MINUTE, start_departure_time, end_arrival_time) AS trip_time
                FROM
                preComputedTripDetails
                WHERE
                    start_stop_id = ?
                    AND end_stop_id = ?
                    AND route_id = ?
                    AND start_departure_time > ?
                ORDER BY
                    start_departure_time ASC

                LIMIT 1;

                        """;
    }

    private static TripInfo fetchTripDetails(Connection conn, String query, Object... params) {
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]); // Simplified setting parameters

            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new TripInfo(
                            rs.getString("route_id"),
                            rs.getString("route_short_name"),
                            rs.getString("trip_id"),
                            rs.getString("start_stop_id"), // start stop ID
                            rs.getString("end_stop_id"), // end stop ID
                            rs.getString("start_departure_time"),
                            rs.getString("end_arrival_time"),
                            rs.getInt("trip_time"));

                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;

    }

    private static void insertIntoTempTransfer(Connection conn, TripInfo firstTrip, TripInfo secondTrip,
            int timeToDestination, double distanceToStartBusstop) {
        String insert = """
                INSERT INTO tempTransfer (
                    first_start_bus_stop_id, first_end_bus_stop_id, first_route_id, first_route_short_name, first_trip_id, first_departure_time, first_arrival_time, first_trip_time,
                    second_start_bus_stop_id, second_end_bus_stop_id, second_route_id, second_route_short_name, second_trip_id, second_departure_time, second_arrival_time, second_trip_time,distanceToFirstBusstop
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?);
                """;

        try (PreparedStatement pstmt = conn.prepareStatement(insert)) {

            pstmt.setString(1, firstTrip.getStartStopId());
            pstmt.setString(2, firstTrip.getEndStopId());
            pstmt.setString(3, firstTrip.getRouteId());
            pstmt.setString(4, firstTrip.getBusNumber());
            pstmt.setString(5, firstTrip.getTripId());
            pstmt.setString(6, firstTrip.getStartDepartureTime());
            pstmt.setString(7, firstTrip.getEndArrivalTime());
            pstmt.setInt(8, firstTrip.getTripTime());

            long timeInMs = secondTrip.getArrTimeInMs(); // Get time in milliseconds since
                                                         // the epoch
            long timeToAdd = timeToDestination * 60 * 1000; // Convert minutes to milliseconds

            // Create a new Time object with the added time
            Time newTime = new Time(timeInMs + timeToAdd);

            // Use the new Time object in your PreparedStatement
            pstmt.setTime(15, newTime);

            pstmt.setString(9, secondTrip.getStartStopId());
            pstmt.setString(10, secondTrip.getEndStopId());
            pstmt.setString(11, secondTrip.getRouteId());
            pstmt.setString(12, secondTrip.getBusNumber());
            pstmt.setString(13, secondTrip.getTripId());
            pstmt.setString(14, secondTrip.getStartDepartureTime());
            pstmt.setTime(15, newTime);
            pstmt.setInt(16, secondTrip.getTripTime());
            pstmt.setDouble(17, distanceToStartBusstop);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // }

}
