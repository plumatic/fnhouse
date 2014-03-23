# Guesthouse

A simple example project that shows how to use fnhouse with ring.  See
`guesthouse.core` for the server, `guesthouse.guestbook` for the handlers,
`guesthouse.schemas` for the coercion middleware, `guesthouse.core-test` for
test usage.

The application is a guestbook where users can add, search for, modify, and
delete guestbook entries.  Each guestbook `Entry` is represented as a map with
keyword keys containing a name, age, and programming language (clj, cljs).  The
`Entry` has a different server and client representation.  On the server, the
`Entry` just has a single field for the name, whereas the `ClientEntry` has a
first and last name.  Serverside `Entry`s are coerced into `ClientEntry`s via a
custom Schema cooercion middleware (see `guesthouse.schemas` for implementation
details).
