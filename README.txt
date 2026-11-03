PARKWISE - PARKING MANAGEMENT WEB APPLICATION

Parkwise is a local web application built with Java 17, Spring Boot, HTML, CSS, JavaScript and SQLite. It is a single-user prototype for managing five parking slots.

QUICK START - NO MAVEN NEEDED
1. Install Java 17 or later. Check with: java -version
2. Open a terminal in this ParkwiseWeb folder.
3. Run: java -jar parkwise.jar
4. Open http://localhost:8080 in a web browser.
5. Keep the terminal open while using the app. Press Ctrl+C in the terminal to stop it.

SIGNING IN
The first time you open the app it asks you to create the administrator account. There is no default password. After that everyone signs in. An administrator can add, view, edit and delete users on the Users page. Staff can use bookings and change their own name and password on the My account page. Five wrong passwords lock that username for one minute. Passwords are stored as salted hashes, so a forgotten password cannot be read back: an administrator can set a new one.

If port 8080 is already in use, run: java -jar parkwise.jar --server.port=8081
Then open http://localhost:8081 instead.

The first run creates parking.db in the directory from which the command is run. Keep running from this folder to retain your records. Do not delete parking.db unless you want to remove the saved bookings. To bring over bookings from the earlier console project, back up that project's parking.db and copy it into this folder before starting the web application. Older records without a vehicle type will be marked CAR.

SOURCE AND REBUILD
Source code is in src/main. pom.xml defines the dependencies. With JDK 17+, Maven and an internet connection for the first dependency download, run:

    mvn test
    mvn package
    java -jar target/parking-web-1.0.0.jar

FEATURES
- Book one of five slots for 1-5 hours and see availability before confirming.
- Search by registration, booking ID, or slot number.
- Cancel a future booking; check in at any time; check out after check-in.
- Extend an active booking up to a maximum total of five hours, subject to availability.
- Open a receipt and use the browser Print / Save as PDF option.
- Dashboard counts and completed booking charges.

RATES (PER HOUR)
                 09:00 to 18:00     Other hours
Car                 Rs. 50             Rs. 30
Two-wheeler         Rs. 30             Rs. 20
A discount of 10% applies to bookings of 4 or 5 hours. Each hour is priced at the rate applicable when it starts. The full booking charge is recalculated after an extension.

HOW THE PROJECT WORKS
The browser sends requests to Spring Boot REST endpoints under /api. The controller checks input, calculates prices and uses JDBC with parameterized SQL to manage the local SQLite bookings table. The web page reads JSON responses to update its dashboard and table. The application binds to localhost and is intended for use on one computer.

IMAGES
The five photos in src/main/resources/static/images are from Unsplash under the Unsplash License: Claudio Schwarz (parking-hall.jpg, scooter.jpg), Benjamin Cheng (parking-lot-aerial.jpg), Sara Kurfess (cars-row.jpg) and Deski Jayantoro (scooters-row.jpg).

LIMITATIONS
There is no online payment, refund or late fee. Sign-in sessions end after 30 minutes without activity or when the server restarts. Bookings can be made up to 90 days ahead. Displayed charges are booking charges, not confirmed revenue or proof of payment. All five slots are shared across vehicle types. Start times are future whole hours; check-in is not limited to the reserved period. A checked-in vehicle that has stayed past its end blocks the slot until check-out. Cancellation is allowed only before check-in and before the booking starts. Checked-out slots become available for the remainder of the reservation with no automatic refund. No-shows stay BOOKED in history. Time uses the computer's local clock and does not handle time zones or daylight-saving changes. Run only one server instance against a database; this prototype does not support concurrent processes. Sign-in protects this local prototype, but it uses plain HTTP, so do not expose it publicly.
