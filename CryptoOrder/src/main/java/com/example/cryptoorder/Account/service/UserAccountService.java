package com.example.cryptoorder.Account.service;

import com.example.cryptoorder.Account.entity.Account;
import com.example.cryptoorder.Account.entity.KRWAccount;
import com.example.cryptoorder.Account.entity.NaverPoint;
import com.example.cryptoorder.Account.entity.User;
import com.example.cryptoorder.Account.repository.AccountRepository;
import com.example.cryptoorder.Account.repository.KRWAccountBalanceRepository;
import com.example.cryptoorder.Account.repository.NaverPointRepository;
import com.example.cryptoorder.Account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final PasswordEncoder passwordEncoder;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final KRWAccountBalanceRepository krwRepository;
    private final NaverPointRepository naverPointRepository;

    //User랑 Account는 ID가 같음

    //회원 가입
    @Transactional(rollbackFor = Exception.class)
    public User createFullAccount(String name, String phone, LocalDate age, String loginId, String password) {
        return createFullAccount(name, phone, age, loginId, password, "MEMBER", null);
    }

    @Transactional(rollbackFor = Exception.class)
    public User createFullAccount(
            String name,
            String phone,
            LocalDate age,
            String loginId,
            String password,
            String authProvider,
            String externalUserId
    ) {
        validateCreateAccountInput(name, phone, age, loginId, password);

        if (accountRepository.existsByUserLoginId(loginId)) {
            throw new IllegalArgumentException("이미 사용 중인 로그인 아이디입니다.");
        }

        String resolvedProvider = (authProvider == null || authProvider.isBlank()) ? "MEMBER" : authProvider;

        // 1. User 생성
        User newUser = User.builder()
                        .userName(name)
                        .phoneNumber(phone)
                        .userAge(age)
                        .isActive(true)
                        .build();
        userRepository.save(newUser);

        // 2. Account (로그인 정보) 생성 및 User 연결
        Account newAccount = Account.builder()
                .user(newUser)
                .userLoginId(loginId)
                .userLoginPw(passwordEncoder.encode(password))
                .authProvider(resolvedProvider)
                .externalUserId(externalUserId)
                .build();
        try {
            accountRepository.saveAndFlush(newAccount);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 사용 중인 로그인 아이디입니다.");
        }

        // 3. 포인트 지갑 생성
        NaverPoint newPoint = NaverPoint.builder()
                .account(newAccount)
                .balance(0L)
                .isActive(true)
                .build();
        naverPointRepository.save(newPoint);

        return newUser;
    }

    //회원 탈퇴
    @Transactional(rollbackFor = Exception.class)
    public User deleteUser(UUID userId) {
        // 1. 사용자 존재 여부 조회
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found!"));

        if (!user.isActive()) {
            throw new IllegalStateException("이미 탈퇴 처리된 사용자입니다.");
        }

        List<KRWAccount> userKRWAccounts = krwRepository.findAllByUserWithLock(user);
        // 2. 잔액 여부 조회
        if(hasNonZeroBalance(userKRWAccounts)){
            throw new IllegalStateException("Cannot delete user with non-zero balance in KRW accounts.");
        }

        // 3. 계좌 비활성화
        deactivateAllAccounts(userKRWAccounts);

        // 사용자 계정 조회
        Optional<Account> userAccount = accountRepository.findByUser(user);

        // 4. NaverPoint 지갑 존재하면 삭제
        if(userAccount.isPresent()){
            UUID accountId = userAccount.get().getId();
            naverPointRepository.findById(accountId).ifPresent(NaverPoint::closeAccount);
        }

        // 5. User 삭제
        user.closeUser();

        return user;

    }

    /**
     * 로그인 (ID/PW 검증 후 User 반환)
     */
    @Transactional(readOnly = true)
    public User login(String loginId, String password) {
        // 1. 아이디로 계정 조회
        Account account = accountRepository.findByUserLoginId(loginId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 아이디입니다."));

        // 2. 비밀번호 검증 (입력받은 비밀번호와 암호화된 비밀번호 비교)
        if (!passwordEncoder.matches(password, account.getUserLoginPw())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 3. 탈퇴한 회원인지 확인 (선택 사항)
        User user = account.getUser();
        if (!user.isActive()) {
            throw new IllegalStateException("탈퇴한 사용자입니다.");
        }

        // 4. 검증 완료된 사용자 객체 반환
        return user;
    }

    private void validateCreateAccountInput(String name, String phone, LocalDate age, String loginId, String password) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다.");
        }

        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("휴대폰 번호는 필수입니다.");
        }
        if (!phone.matches("^010-\\d{4}-\\d{4}$")) {
            throw new IllegalArgumentException("휴대폰 번호 형식이 올바르지 않습니다. (010-1234-5678)");
        }

        if (age == null || age.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("유효한 생년월일을 입력해주세요.");
        }

        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("로그인 아이디는 필수입니다.");
        }

        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다.");
        }
    }

    private boolean hasNonZeroBalance(List<KRWAccount> accounts){
        for (KRWAccount account : accounts){
            Long balance = account.getBalance();
            if (balance == null || balance != 0L) {
                return true;
            }
        }
        return false;
    }

    private void deactivateAllAccounts(List<KRWAccount> accounts){
        for (KRWAccount userKRWAccount : accounts){
            userKRWAccount.closeAccount();
        }
    }



}
