package com.javarush;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javarush.dao.CityDAO;
import com.javarush.dao.CountryDAO;
import com.javarush.domain.City;
import com.javarush.domain.Country;
import com.javarush.domain.CountryLanguage;
import com.javarush.redis.CityCountry;
import com.javarush.redis.Language;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    private final SessionFactory sessionFactory;
    private final RedisClient redisClient;

    private final ObjectMapper mapper;

    private final CityDAO cityDAO;
    private final CountryDAO countryDAO;

    public Main() {
        sessionFactory = prepareRelationalDb();
        cityDAO = new CityDAO(sessionFactory);
        countryDAO = new CountryDAO(sessionFactory);

        redisClient = prepareRedisClient();
        mapper = new ObjectMapper();
    }

    public static void main(String[] args) {
        Main main = new Main();
        List<City> cities = main.fetchData(main);
        List<CityCountry> preparedData = main.transformData(cities);
        main.sessionFactory.getCurrentSession().close();
        main.pushToRedis(preparedData);
        //выбираем случайных 10 id городов
        //так как мы не делали обработку невалидных ситуаций, используй существующие в БД id
        List<Integer> ids = List.of(3, 2545, 123, 4, 189, 89, 3458, 1189, 10, 102);

        long startRedis = System.currentTimeMillis();
        main.testRedisData(ids);
        long stopRedis = System.currentTimeMillis();

        long startMysql = System.currentTimeMillis();
        main.testMysqlData(ids);
        long stopMysql = System.currentTimeMillis();

        System.out.printf("%s:\t%d ms\n", "Redis", (stopRedis - startRedis));
        System.out.printf("%s:\t%d ms\n", "MySQL", (stopMysql - startMysql));

        main.shutdown();
    }

    private SessionFactory prepareRelationalDb() {
        final SessionFactory sessionFactory;
        Properties properties = new Properties();
        properties.put(Environment.DIALECT, "org.hibernate.dialect.MySQL8Dialect");
        properties.put(Environment.DRIVER, "com.p6spy.engine.spy.P6SpyDriver");
        properties.put(Environment.URL, "jdbc:p6spy:mysql://localhost:3306/world");
        properties.put(Environment.USER, "root");
        properties.put(Environment.PASS, "password");
        properties.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
        properties.put(Environment.HBM2DDL_AUTO, "validate");
        properties.put(Environment.STATEMENT_BATCH_SIZE, "100");

        sessionFactory = new Configuration()
                .addAnnotatedClass(City.class)
                .addAnnotatedClass(Country.class)
                .addAnnotatedClass(CountryLanguage.class)
                .addProperties(properties)
                .buildSessionFactory();
        return sessionFactory;
    }
    private RedisClient prepareRedisClient() {
        RedisClient client = RedisClient.create(RedisURI.create("localhost", 6379));
        try (StatefulRedisConnection<String, String> connect = client.connect()) {
            System.out.println("\nConnected to Redis\n");
        }
        return client;
    }
    private void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
    private List<City> fetchData(Main main) {
        try (Session session = main.sessionFactory.getCurrentSession()) {
            List<City> allCities = new ArrayList<>();
            session.beginTransaction();
            List<Country> countries = main.countryDAO.getAll();
            int total = main.cityDAO.getTotalCount();
            int step = 500;
            for (int i = 0; i < total; i += step) {
                allCities.addAll(main.cityDAO.getItems(i, step));
            }
            session.getTransaction().commit();
            return allCities;
        }
    }
    private List<CityCountry> transformData(List<City> cities) {
        return cities.stream().map(city -> {
            CityCountry res = new CityCountry();
            res.setId(city.getId());
            res.setName(city.getName());
            res.setPopulation(city.getPopulation());
            res.setDistrict(city.getDistrict());

            Country country = city.getCountryId();
            res.setAlternativeCountryCode(country.getCode_2());
            res.setContinent(country.getContinent());
            res.setCountryCode(country.getCode());
            res.setCountryName(country.getName());
            res.setCountryPopulation(country.getPopulation());
            res.setCountryRegion(country.getRegion());
            res.setCountrySurfaceArea(country.getSurfaceArea());
            Set<CountryLanguage> set = country.getLanguages();
            Set<Language> languages = set.stream().map(cl -> {
                Language language = new Language();
                language.setLanguage(cl.getLanguage());
                language.setPercentage(cl.getPercentage());
                language.setOfficial(cl.getOfficial());
                return language;
            }).collect(Collectors.toSet());
            res.setLanguages(languages);
            return res;
        }).collect(Collectors.toList());
    }
    private void pushToRedis(List<CityCountry> data) {
        try (StatefulRedisConnection<String, String> connect = redisClient.connect()) {
            RedisCommands<String, String> sync = connect.sync();
            for (CityCountry cc : data) {
                try {
                    sync.set(String.valueOf(cc.getId()), mapper.writeValueAsString(cc));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void testRedisData(List<Integer> ids) {
        try (StatefulRedisConnection<String, String> connect = redisClient.connect()) {
            RedisCommands<String, String> sync = connect.sync();
            for (Integer id : ids) {
                String s = sync.get(String.valueOf(id));
                try {
                    mapper.readValue(s, CityCountry.class);
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void testMysqlData(List<Integer> ids) {
        try (Session session = sessionFactory.getCurrentSession()) {
            session.beginTransaction();
            for (Integer id : ids) {
                City city = cityDAO.getById(id);
                Set<CountryLanguage> languages = city.getCountryId().getLanguages();
            }
            session.getTransaction().commit();
        }
    }
}
