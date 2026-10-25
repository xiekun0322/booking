package com.starscreen.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.starscreen.entity.Cinema;

import io.lettuce.core.dynamic.annotation.Param;

public interface CinemaRepository extends JpaRepository<Cinema, Long> {
    List<Cinema> findByBrand(String brand);
    List<Cinema> findByDistrict(String district);

    @Query("SELECT c FROM Cinema c WHERE (:brand IS NULL OR c.brand = :brand) AND (:district IS NULL OR c.district = :district)")
    List<Cinema> findByBrandAndDistrict(@Param("brand") String brand, @Param("district") String district);    
}
