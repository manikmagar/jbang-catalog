///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS info.picocli:picocli:4.5.0
//DEPS com.google.api-client:google-api-client:1.23.0
//DEPS com.google.oauth-client:google-oauth-client-jetty:1.23.0
//DEPS com.google.apis:google-api-services-calendar:v3-rev305-1.23.0
//DEPS org.slf4j:slf4j-nop:1.7.30

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

@Command(name = "MuleMeetupCalendar", mixinStandardHelpOptions = true, version = "MuleMeetupCalendar 0.1",
        description = "MuleMeetupCalendar made with jbang")
class MuleMeetupCalendar implements Callable<Integer> {

    private static final String APPLICATION_NAME = "Google Calendar API Java Quickstart";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = ".tokens";
    private static final String MULE_MEETUP_CALENDAR_ID = "idc4qavc8b81c9oop81obrs27k@group.calendar.google.com";

    /**
     * Global instance of the scopes required by this quickstart.
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);
    private static final String CREDENTIALS_FILE_PATH = "./.local/credentials.json";


    @Parameters(index = "0", description = "Credentials file", defaultValue = "./.local/credentials.json")
    private File credentialsFile;

    public static void main(String... args) {
        int exitCode = new CommandLine(new MuleMeetupCalendar()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        // Build a new authorized API client service.
        List<MeetupEvent> upcomingEvents = getUpcomingEvents();
        System.out.println("Number of upcoming events: " + upcomingEvents.size());
        if(upcomingEvents.isEmpty()) return 0;
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();
        DateTime now = new DateTime(System.currentTimeMillis());
        Events events = service.events().list(MULE_MEETUP_CALENDAR_ID)
                .setMaxResults(100)
                .setTimeMin(now)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();
        Map<String, Event> calendarEvents = events.getItems().stream().collect(Collectors.toMap(Event::getId, Function.identity()));
//        Map<String, MeetupEvent> upcomingEventsMap = upcomingEvents.stream().collect(Collectors.toMap(MeetupEvent::getId, Function.identity()));
//        calendarEvents.keySet().stream().map(id -> id.substring(18))
//                .filter(id -> !upcomingEventsMap.containsKey(id))
//                .forEach(id -> {
//                    try {
//                        service.events().delete(MULE_MEETUP_CALENDAR_ID, "mule0meetup0event0".concat(id));
//                    } catch (IOException e) {
//                        System.err.println("Unable to remove event");
//                    }
//                });
        for (MeetupEvent meetupEvent: upcomingEvents) {
            String eventId = "mule0meetup0event0".concat(meetupEvent.getId());
            String rsvp = " <br/> <b>RSVP here</b> - ".concat(meetupEvent.getUrl()).concat("<br/> <b>NOTE:</b> This is just an informational calendar entry. Please visit actual RSVP page to register for the event.");
            Event toInsert = new Event()
                    .setId(eventId)
                    .setSummary(meetupEvent.getTitle())
                    .setDescription(meetupEvent.getDescription_short().concat(rsvp))
                    .setStart(new EventDateTime().setDateTime(DateTime.parseRfc3339(meetupEvent.getStartTimeString())))
                    .setEnd(new EventDateTime()
                            .setDateTime(DateTime.parseRfc3339(
                                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(meetupEvent.getStartTime().plusHours(1)))));
            Event inserted = new Event().setId("empty");
            if(calendarEvents.containsKey(eventId)) {
                inserted = updateEvent(service, meetupEvent, eventId, toInsert);
            }else {
                try{
                    System.out.println("New event, inserting - " + meetupEvent);
                    inserted = service.events().insert(MULE_MEETUP_CALENDAR_ID, toInsert).execute();
                }catch (GoogleJsonResponseException exception) {
                    if (exception.getStatusCode() == 409) {
                        inserted = updateEvent(service, meetupEvent, eventId, toInsert);
                    }
                }

            }
            System.out.println(inserted);
            System.out.println(inserted.getHtmlLink());
        }

        return 0;
    }

    private Event updateEvent(Calendar service, MeetupEvent meetupEvent, String eventId, Event toInsert) throws IOException {
        Event inserted;
        System.out.println("Event is already registered, updating - " + meetupEvent);
        inserted = service.events().update(MULE_MEETUP_CALENDAR_ID, eventId, toInsert).execute();
        return inserted;
    }

    private List<MeetupEvent> getUpcomingEvents() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://meetups.mulesoft.com/api/search/?result_types=upcoming_event&country_code=Earth"))
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        Map meetupEventsResponse = JSON_FACTORY.fromString(response.body(), Map.class);
        List<MeetupEvent> results = ((List<Map>) meetupEventsResponse.getOrDefault("results", Collections.emptyList())).stream().map(this::toMeetupEvent).collect(Collectors.toList());
        return results;
    }
    private MeetupEvent toMeetupEvent(Map eventData){
        MeetupEvent meetupEvent = new MeetupEvent();
        meetupEvent.setId(eventData.get("id").toString());
        meetupEvent.setTitle(eventData.get("title").toString());
        meetupEvent.setDescription_short(eventData.get("description_short").toString());
        meetupEvent.setUrl(eventData.get("url").toString());
        meetupEvent.setStartTimeString(eventData.get("start_date").toString());
        meetupEvent.setStartTime(ZonedDateTime.parse(meetupEvent.getStartTimeString()));
        MeetupChapter chapter = new MeetupChapter();
        chapter.setCity(eventData.get("city").toString());
        meetupEvent.setChapter(chapter);
        return meetupEvent;

    }
    /**
     * Creates an authorized Credential object.
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        // Load client secrets.
        InputStream in = new FileInputStream(credentialsFile);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static class MeetupEventsResponse{
        private List<MeetupEvent> results;
        private Map location;

        public Map getLocation() {
            return location;
        }

        public void setLocation(Map location) {
            this.location = location;
        }

        public List<MeetupEvent> getResults() {
            return results;
        }

        public void setResults(List<MeetupEvent> results) {
            this.results = results;
        }
    }
    public static class MeetupEvent {
        private String id;
        private String title;
        private String description_short;
        private String url;
        private ZonedDateTime startTime;
        private String startTimeString;
        private ZonedDateTime endTime;
        private MeetupChapter chapter;

        public String getStartTimeString() {
            return startTimeString;
        }

        public void setStartTimeString(String startTimeString) {
            this.startTimeString = startTimeString;
        }

        public ZonedDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(ZonedDateTime startTime) {
            this.startTime = startTime;
        }

        public ZonedDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(ZonedDateTime endTime) {
            this.endTime = endTime;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription_short() {
            return description_short;
        }

        public void setDescription_short(String description_short) {
            this.description_short = description_short;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public MeetupChapter getChapter() {
            return chapter;
        }

        public void setChapter(MeetupChapter chapter) {
            this.chapter = chapter;
        }

        @Override
        public String toString() {
            return "MeetupEvent{" +
                    "id='" + id + '\'' +
                    ", title='" + title + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }
    public static class MeetupChapter {
        private String chapter_location;
        private String city;
        private String country;

        public String getChapter_location() {
            return chapter_location;
        }

        public void setChapter_location(String chapter_location) {
            this.chapter_location = chapter_location;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }
    }
}
