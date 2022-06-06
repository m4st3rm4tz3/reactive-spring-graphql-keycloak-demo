package com.example.demo.person;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("persons")
public class Person {

    @Id
    private String id;

    private String firstName;

    private String lastName;

    private String eMail;

}
