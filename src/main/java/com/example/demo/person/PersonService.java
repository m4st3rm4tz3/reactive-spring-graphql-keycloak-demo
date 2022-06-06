package com.example.demo.person;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class PersonService {

    private final PersonRepository repository;

    public Flux<Person> findAllPersons() {
        return repository.findAll();
    }

}
