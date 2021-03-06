package com.cbsys.iclock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cbsys.iclock.domain.Staff;

public interface StaffDao extends JpaRepository<Staff, Long> {
	public List<Staff> findByPinAndCorpToken(String pin, String corpToken);

	@Query("select s.id from Staff s  where s.corpToken=?1")
	public List<Long> getIdsByCorpToken(String corpToken);
}
