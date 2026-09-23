package com.example.demo.modules.user.mapper;

import com.example.demo.modules.user.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper {


    List<User> findAll();

    User findById(Long id);
    int insert(User user);
    User findByUsername(String username);
}
