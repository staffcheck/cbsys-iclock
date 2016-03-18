package com.cbsys.iclock.service;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cbsys.iclock.AttendanceConstants;
import com.cbsys.iclock.domain.AttRecord;
import com.cbsys.iclock.domain.Oplog;
import com.cbsys.iclock.domain.Staff;
import com.cbsys.iclock.domain.StaffFacePrint;
import com.cbsys.iclock.domain.StaffFingerPrint;
import com.cbsys.iclock.repository.AttRecordDao;
import com.cbsys.iclock.repository.OplogDao;
import com.cbsys.iclock.repository.StaffDao;
import com.cbsys.iclock.repository.StaffFacePrintDao;
import com.cbsys.iclock.repository.StaffFingerPrintDao;
import com.cbsys.iclock.utils.DateUtil;
import com.cbsys.iclock.utils.LogUtil;
import com.cbsys.iclock.vo.DeviceInfo;

@Service
@Transactional
public class ClockService {
	private static final Log logger = LogFactory.getLog(ClockService.class);
	@Autowired
	private AttRecordDao attRecordDao;
	@Autowired
	private StaffDao staffDao;
	@Autowired
	private StaffFingerPrintDao staffFingerPrintDao;
	@Autowired
	private StaffFacePrintDao staffFacePrintDao;
	@Autowired
	private OplogDao oplogDao;
	private static final Pattern STAFF_NO_CARD_PATTERN = Pattern.compile("\\[0*\\]");
	private static final Object v = new Object();
	private static final Map<String, Object> PROCESSING_ATTRECORDS_DEVICES = new ConcurrentHashMap<String, Object>(64);

	public void processAttRecords(String sn, Collection<AttRecord> records) throws Exception {
		int i = 1;
		while (PROCESSING_ATTRECORDS_DEVICES.containsKey(sn)) {
			if (i > 6)// 等待超过15s
				throw new Exception("PROCESSING " + sn + " DEVICE !!! Maybe next time.");
			try {
				Thread.sleep(1500);
			} catch (Exception e) {
			}
			i++;
		}
		PROCESSING_ATTRECORDS_DEVICES.put(sn, v);
		logger.info("+++Insert into ---> PROCESSING_ATTRECORDS_DEVICES：" + sn);
		try {
			for (AttRecord ar : records)
				attRecordDao.save(ar);
		} finally {
			PROCESSING_ATTRECORDS_DEVICES.remove(sn);
			logger.info("---removing from ------> PROCESSING_ATTRECORDS_DEVICES：" + sn);
		}
	}

	public void processUserInfos(DeviceInfo device, Collection<String> userInfos) {
		Map<String, Staff> staffMaps = new HashMap<String, Staff>();
		Timestamp cur = new Timestamp(System.currentTimeMillis());
		for (String ui : userInfos) {
			String[] tokens = ui.split(AttendanceConstants.RESP_TEXT_T);
			if (ui.indexOf(AttendanceConstants.OPSTAMP_USER) == 0) {// 上传的是用户信息
				processUSER(device, tokens, staffMaps, cur, ui);
			} else if (ui.indexOf(AttendanceConstants.OPSTAM_FP) == 0) {// 上传的是指纹格式
				processFP(device, tokens, staffMaps, cur, ui);
			} else if (ui.indexOf(AttendanceConstants.OPSTAM_FACE) == 0) {// 上传的是人脸格式
				processFACE(device, tokens, staffMaps, cur);
			} else if (ui.indexOf(AttendanceConstants.OPSTAM_OPLOG) == 0) {// 上传的是操作日志
				processOPLOG(device, tokens, cur);
			}
		}
	}

	/**
	 *
	 * 格式：FACE PIN=1 FID=0 SIZE=372 VALID=1 TMP=xxx
	 *
	 * @param tokens
	 *            0:FACE PIN=xxx 1:FID=xxx 2:Size=xxx 3:Valid=xxx 4:TMP=xxx
	 */
	public void processFACE(DeviceInfo d, String[] tokens, Map<String, Staff> staffMaps, Timestamp cur) {
		if (tokens == null || tokens.length != 5) {
			logger.info("Wrong FACE Data Format：" + d.getSn());
			return;
		}
		String attNo = tokens[0].substring(9);
		Staff staff = staffMaps.get(attNo);
		if (staff == null) {
			List<Staff> staffs = staffDao.findByPinAndCorpToken(attNo, d.getCorpToken());
			if (CollectionUtils.isNotEmpty(staffs))
				staffMaps.put(attNo, staffs.get(0));
		}
		try {
			String tmp = tokens[4].substring(4);
			int fid = Integer.parseInt(tokens[1].substring(4));
			List<StaffFacePrint> list = staffFacePrintDao.findByPinAndFidAndCorpToken(attNo, fid, d.getCorpToken());
			if (CollectionUtils.isNotEmpty(list))
				staffFacePrintDao.deleteInBatch(list);
			StaffFacePrint sfp = new StaffFacePrint();
			sfp.setFid(fid);
			sfp.setSize(Integer.parseInt(tokens[2].substring(5)));
			sfp.setValid(Integer.parseInt(tokens[3].substring(6)));
			sfp.setTmp(tmp);
			if (staff != null)
				sfp.setStaffid(staff.getId());
			sfp.setSerialNumber(d.getSn());
			sfp.setCorpToken(d.getCorpToken());
			sfp.setCreateTime(cur);
			sfp.setUpdateTime(cur);
			staffFacePrintDao.save(sfp);
		} catch (Exception e) {
			logger.error(LogUtil.stackTraceToString(e));
		}
	}

	/**
	 * 格式：<b>FP PIN=1 FID=0 Size=372 Valid=1 TMP=xxx</b>（多个字段之间是用制表符 \t 分隔的）
	 *
	 * @param device
	 * @param tokens
	 *            0:FP PIN=xxx 1:FID=xxx 2:Size=xxx 3:Valid=xxx 4:TMP=xxx
	 * @param staffMaps
	 */
	public void processFP(DeviceInfo d, String[] tokens, Map<String, Staff> staffMaps, Timestamp cur, String line) {
		if (tokens == null || tokens.length != 5) {
			logger.info("Wrong FP Data Format：" + d.getSn() + " $$$$ " + line);
			return;
		}
		String attNo = tokens[0].substring(7);
		Staff staff = staffMaps.get(attNo);
		if (staff == null) {
			List<Staff> staffs = staffDao.findByPinAndCorpToken(attNo, d.getCorpToken());
			if (CollectionUtils.isNotEmpty(staffs))
				staffMaps.put(attNo, staffs.get(0));
		}
		try {
			// 判断更新指纹表
			String tmp = tokens[4].substring(4);
			int fid = Integer.parseInt(tokens[1].substring(4));
			List<StaffFingerPrint> fps = staffFingerPrintDao.findByPinAndFidAndCorpToken(attNo, fid, d.getCorpToken());
			if (CollectionUtils.isEmpty(fps)) {
				StaffFingerPrint fp = new StaffFingerPrint();
				fp.setCreateTime(cur);
				fp.setUpdateTime(cur);
				fp.setFid(fid);
				fp.setSize(Integer.parseInt(tokens[2].substring(5)));
				fp.setValid(Integer.parseInt(tokens[3].substring(6)));
				fp.setTmp(tmp);
				fp.setSerialNumber(d.getSn());
				fp.setCorpToken(d.getCorpToken());
				if (staff != null)
					fp.setStaffid(staff.getId());
				staffFingerPrintDao.save(fp);
			} else if (!fps.get(0).getTmp().equals(tmp)) {
				StaffFingerPrint fp = fps.get(0);
				fp.setUpdateTime(cur);
				fp.setSize(Integer.parseInt(tokens[2].substring(5)));
				fp.setValid(Integer.parseInt(tokens[3].substring(6)));
				fp.setTmp(tmp);
				fp.setSerialNumber(d.getSn());
				fp.setCorpToken(d.getCorpToken());
				if (staff != null)
					fp.setStaffid(staff.getId());
				staffFingerPrintDao.save(fp);
			}
		} catch (Exception e) {
			logger.error(LogUtil.stackTraceToString(e));
		}
	}

	/**
	 * USER PIN=982 Name=Richard Passwd=9822 Card=[09E4812202] Grp=1 TZ=
	 */
	public void processUSER(DeviceInfo d, String[] tokens, Map<String, Staff> staffMaps, Timestamp cur, String line) {
		if (tokens == null || tokens.length != 6) {
			logger.info("Wrong UserInfo Data Format: " + d.getSn() + " $$$$ " + line);
			return;
		}
		String attNo = tokens[0].substring(9);
		Staff staff = staffMaps.get(attNo);
		if (staff == null) {
			List<Staff> staffs = staffDao.findByPinAndCorpToken(attNo, d.getCorpToken());
			if (CollectionUtils.isNotEmpty(staffs))
				staff = staffs.get(0);
			else {
				staff = new Staff();
				staff.setCreateTime(cur);
			}
			staffMaps.put(attNo, staffs.get(0));
		}
		try {
			staff.setCorpToken(d.getCorpToken());
			staff.setSerialNumber(d.getSn());
			staff.setPin(attNo);
			staff.setName(tokens[1].substring(5));
			staff.setUpdateTime(cur);
			staff.setPassword(tokens[2].substring(7));
			if (StringUtils.isNotEmpty(tokens[3]) && tokens[3].length() > 5 && !STAFF_NO_CARD_PATTERN.matcher(tokens[3]).find())
				staff.setCard(tokens[3].substring(6).replace("]", ""));
			staff.setGrp(tokens[4].substring(4).trim());
			staff.setTz(tokens[5].substring(3).trim());
			staff.setUpdateTime(cur);
			staffDao.save(staff);
		} catch (Exception e) {
			logger.error(LogUtil.stackTraceToString(e));
		}
	}

	/** 
	 * OPLOG 0 0 289515406 0 0 0 0 
	 * 
	 * For details about specific formats and contents:
	 * OPLOG
	 * Operation log 
	 * Administrator ID
	 * Time
	 * reserve1
	 * reserve2
	 * reserve3
	 * reserve4
	 */
	public void processOPLOG(DeviceInfo device, String[] tokens, Timestamp cur) {
		if (tokens == null || tokens.length == 0)
			return;
		Oplog oplog = new Oplog();
		try {
			oplog.setOpCode(Integer.parseInt(tokens[0].substring(6)));
			oplog.setAdminId(Integer.parseInt(tokens[1]));
			try {
				logger.info(tokens[2]);
				if (tokens[2].indexOf(":") < 0) {
					oplog.setCreateTime(DateUtil.getTimeByZKTimestamp(Long.parseLong(tokens[2])));
				} else {
					oplog.setCreateTime(Timestamp.valueOf(tokens[2]));
				}
			} catch (RuntimeException e) {
				logger.error("Transfer Time failed!!");
				logger.error(LogUtil.stackTraceToString(e));
				oplog.setCreateTime(cur);// 插入当前时间
			}
			if (tokens.length >= 4) {
				oplog.setOpObject1(tokens[3]);
				if (tokens.length >= 5) {
					oplog.setOpObject2(tokens[4]);
					if (tokens.length >= 6) {
						oplog.setOpObject3(tokens[5]);
						if (tokens.length >= 7) {
							oplog.setOpObject4(tokens[6]);
						}
					}
				}
			}
			oplog.setCorpToken(device.getCorpToken());
			oplog.setSerialNumber(device.getSn());
			oplog.setUpdateTime(oplog.getCreateTime());
			oplogDao.save(oplog);
		} catch (Exception e) {
			logger.error(LogUtil.stackTraceToString(e));
		}
	}

}