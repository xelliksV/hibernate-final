package com.javarush.dao;

import com.javarush.domain.Country;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;

import java.util.List;

public class CountryDAO {
    private final SessionFactory sessionFactory;
    public CountryDAO(SessionFactory factory) {
        this.sessionFactory = factory;
    }
    public List<Country> getAll() {
        Query<Country> fromCountry = sessionFactory.getCurrentSession().createQuery("select c from Country c join fetch c.languages", Country.class);
        return fromCountry.list();
    }
}
